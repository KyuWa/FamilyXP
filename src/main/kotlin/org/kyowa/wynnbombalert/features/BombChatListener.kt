package org.kyowa.wynnbombalert.features

import com.google.gson.Gson
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import org.kyowa.wynnbombalert.COLOR_CODE_REGEX
import org.kyowa.wynnbombalert.WynnBombAlert
import org.kyowa.wynnbombalert.config.BombAlertConfig
import org.kyowa.wynnbombalert.stripPrivateUse
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
    private const val EXPIRE_DELETE_DELAY_SECONDS = 5L

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val raw = message.string
                .replace(COLOR_CODE_REGEX, "")
                .stripPrivateUse()
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

                // Countdown edits: 19 minutes remaining down to 1 minute remaining
                for (minutesElapsed in 1 until BOMB_DURATION_MINUTES) {
                    val minutesLeft = BOMB_DURATION_MINUTES - minutesElapsed
                    scheduler.schedule({
                        runCatching {
                            val content = "$boldMessage ⏰ $minutesLeft minute${if (minutesLeft == 1) "" else "s"} remaining"
                            patch(webhookUrl, messageId, content)
                        }.onFailure { WynnBombAlert.LOGGER.error("Failed to update countdown (messageId=$messageId)", it) }
                    }, minutesElapsed.toLong(), TimeUnit.MINUTES)
                }

                // At t=20min: edit to EXPIRED
                scheduler.schedule({
                    runCatching {
                        patch(webhookUrl, messageId, "$boldMessage ❌ EXPIRED")
                    }.onFailure { WynnBombAlert.LOGGER.error("Failed to mark expired (messageId=$messageId)", it) }
                }, BOMB_DURATION_MINUTES.toLong(), TimeUnit.MINUTES)

                // At t=20min+5s: delete the message
                scheduler.schedule({
                    runCatching {
                        val deleteRequest = HttpRequest.newBuilder()
                            .uri(URI.create("$webhookUrl/messages/$messageId"))
                            .DELETE()
                            .build()
                        HTTP_CLIENT.send(deleteRequest, HttpResponse.BodyHandlers.ofString())
                    }.onFailure { WynnBombAlert.LOGGER.error("Failed to delete expired message (messageId=$messageId)", it) }
                    scheduler.shutdown()
                }, BOMB_DURATION_MINUTES * 60L + EXPIRE_DELETE_DELAY_SECONDS, TimeUnit.SECONDS)

            }.onFailure { WynnBombAlert.LOGGER.error("Failed to send Discord webhook", it) }
        }.also { it.isDaemon = true }.start()
    }

    private fun patch(webhookUrl: String, messageId: String, content: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$webhookUrl/messages/$messageId"))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(mapOf("content" to content))))
            .build()
        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
