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
    private const val BOMB_DURATION_SECONDS = 20 * 60        // 1200s
    private const val UPDATE_INTERVAL_SECONDS = 30L
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

    private fun formatTime(totalSeconds: Long): String {
        val mm = totalSeconds / 60
        val ss = (totalSeconds % 60).toString().padStart(2, '0')
        return "$mm:$ss"
    }

    private fun bombContent(boldMessage: String, timeStr: String) =
        "$boldMessage\n```⏰ $timeStr remaining```"

    private fun triggerAlert(message: String) {
        val webhookUrl = BombAlertConfig.config.webhookUrl
        if (webhookUrl.isBlank()) return
        Thread {
            runCatching {
                val boldMessage = "**$message**"

                val initialBody = GSON.toJson(mapOf("content" to bombContent(boldMessage, formatTime(BOMB_DURATION_SECONDS.toLong()))))
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

                // One update every 30 seconds: 19:30, 19:00, ... 0:30
                val totalUpdates = (BOMB_DURATION_SECONDS / UPDATE_INTERVAL_SECONDS).toInt()
                for (step in 1 until totalUpdates) {
                    val secondsElapsed = step * UPDATE_INTERVAL_SECONDS
                    val secondsLeft = BOMB_DURATION_SECONDS - secondsElapsed
                    scheduler.schedule({
                        runCatching {
                            patch(webhookUrl, messageId, bombContent(boldMessage, formatTime(secondsLeft)))
                        }.onFailure { WynnBombAlert.LOGGER.error("Failed to update countdown (messageId=$messageId)", it) }
                    }, secondsElapsed, TimeUnit.SECONDS)
                }

                // At t=20min: EXPIRED
                scheduler.schedule({
                    runCatching {
                        patch(webhookUrl, messageId, "$boldMessage\n```❌ EXPIRED```")
                    }.onFailure { WynnBombAlert.LOGGER.error("Failed to mark expired (messageId=$messageId)", it) }
                }, BOMB_DURATION_SECONDS.toLong(), TimeUnit.SECONDS)

                // At t=20min+5s: delete
                scheduler.schedule({
                    runCatching {
                        val req = HttpRequest.newBuilder()
                            .uri(URI.create("$webhookUrl/messages/$messageId"))
                            .DELETE()
                            .build()
                        HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString())
                    }.onFailure { WynnBombAlert.LOGGER.error("Failed to delete expired message (messageId=$messageId)", it) }
                    scheduler.shutdown()
                }, BOMB_DURATION_SECONDS + EXPIRE_DELETE_DELAY_SECONDS, TimeUnit.SECONDS)

            }.onFailure { WynnBombAlert.LOGGER.error("Failed to send Discord webhook", it) }
        }.also { it.isDaemon = true }.start()
    }

    private fun patch(webhookUrl: String, messageId: String, content: String) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$webhookUrl/messages/$messageId"))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(mapOf("content" to content))))
            .build()
        HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
