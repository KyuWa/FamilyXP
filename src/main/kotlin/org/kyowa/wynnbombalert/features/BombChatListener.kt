package org.kyowa.wynnbombalert.features

import com.google.gson.Gson
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import org.kyowa.wynnbombalert.COLOR_CODE_REGEX
import org.kyowa.wynnbombalert.PRIVATE_USE_REGEX
import org.kyowa.wynnbombalert.WynnBombAlert
import org.kyowa.wynnbombalert.config.BombAlertConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BombChatListener {
    private val BOMB_REGEX = Regex("""^[^:]+has thrown a Combat Experience Bomb on .+""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val HTTP_CLIENT = HttpClient.newHttpClient()
    private val GSON = Gson()

    private var pendingMessage: String? = null
    private var pendingTimestamp: Long = 0
    private const val BUFFER_TTL_MS = 1000L
    private const val BOMB_DURATION_MINUTES = 20

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val raw = message.string
                .replace(COLOR_CODE_REGEX, "")
                .replace(PRIVATE_USE_REGEX, "")
                .replace('\n', ' ')
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()

            val now = System.currentTimeMillis()
            val pending = pendingMessage
            val combined = if (pending != null && (now - pendingTimestamp) < BUFFER_TTL_MS) {
                "$pending $raw"
            } else null

            when {
                BOMB_REGEX.containsMatchIn(raw) -> {
                    triggerAlert(raw)
                    pendingMessage = null
                }
                combined != null && BOMB_REGEX.containsMatchIn(combined) -> {
                    triggerAlert(combined)
                    pendingMessage = null
                }
                else -> {
                    pendingMessage = raw
                    pendingTimestamp = now
                }
            }
        }
    }

    private fun triggerAlert(message: String) {
        val webhookUrl = BombAlertConfig.config.webhookUrl
        if (webhookUrl.isBlank()) return
        Thread {
            runCatching {
                val boldMessage = "**$message**"

                val initialBody = GSON.toJson(mapOf("content" to "$boldMessage ⏰ $BOMB_DURATION_MINUTES minutes remaining"))
                val postRequest = HttpRequest.newBuilder()
                    .uri(URI.create("$webhookUrl?wait=true"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(initialBody))
                    .build()
                val response = HTTP_CLIENT.send(postRequest, HttpResponse.BodyHandlers.ofString())

                @Suppress("UNCHECKED_CAST")
                val messageId = (GSON.fromJson(response.body(), Map::class.java)["id"] as? String)
                    ?: run {
                        WynnBombAlert.LOGGER.error("Could not parse message ID from Discord response: ${response.body()}")
                        return@runCatching
                    }

                val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r).also { it.isDaemon = true }
                }

                for (minutesElapsed in 1..BOMB_DURATION_MINUTES) {
                    val minutesLeft = BOMB_DURATION_MINUTES - minutesElapsed
                    scheduler.schedule({
                        runCatching {
                            val content = if (minutesLeft > 0) {
                                "$boldMessage ⏰ $minutesLeft minute${if (minutesLeft == 1) "" else "s"} remaining"
                            } else {
                                "$boldMessage ❌ EXPIRED"
                            }
                            val patchRequest = HttpRequest.newBuilder()
                                .uri(URI.create("$webhookUrl/messages/$messageId"))
                                .header("Content-Type", "application/json")
                                .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(mapOf("content" to content))))
                                .build()
                            HTTP_CLIENT.send(patchRequest, HttpResponse.BodyHandlers.ofString())
                        }.onFailure { WynnBombAlert.LOGGER.error("Failed to update countdown (messageId=$messageId)", it) }
                        if (minutesLeft == 0) scheduler.shutdown()
                    }, minutesElapsed.toLong(), TimeUnit.MINUTES)
                }
            }.onFailure { WynnBombAlert.LOGGER.error("Failed to send Discord webhook", it) }
        }.also { it.isDaemon = true }.start()
    }
}
