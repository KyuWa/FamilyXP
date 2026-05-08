package org.kyowa.wynnbombalert.features

import com.google.gson.Gson
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import org.kyowa.wynnbombalert.COLOR_CODE_REGEX
import org.kyowa.wynnbombalert.WynnBombAlert
import org.kyowa.wynnbombalert.config.BombAlertConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object BombChatListener {
    private val BOMB_REGEX = Regex(""".+has thrown a Combat Experience Bomb on .+""")
    private val HTTP_CLIENT = HttpClient.newHttpClient()
    private val GSON = Gson()

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            val raw = message.string.replace(COLOR_CODE_REGEX, "").trim()
            if (BOMB_REGEX.containsMatchIn(raw)) {
                val webhookUrl = BombAlertConfig.config.webhookUrl
                if (webhookUrl.isNotBlank()) {
                    sendToDiscord(webhookUrl, raw)
                }
            }
        }
    }

    private fun sendToDiscord(webhookUrl: String, message: String) {
        Thread {
            runCatching {
                val body = GSON.toJson(mapOf("content" to "**$message**"))
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
            }.onFailure { WynnBombAlert.LOGGER.error("Failed to send Discord webhook", it) }
        }.also { it.isDaemon = true }.start()
    }
}
