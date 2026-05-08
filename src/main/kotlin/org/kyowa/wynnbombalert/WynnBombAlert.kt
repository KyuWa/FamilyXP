package org.kyowa.wynnbombalert

import net.fabricmc.api.ClientModInitializer
import org.kyowa.wynnbombalert.commands.WebhookCommand
import org.kyowa.wynnbombalert.config.BombAlertConfig
import org.kyowa.wynnbombalert.features.BombChatListener
import org.slf4j.LoggerFactory

val COLOR_CODE_REGEX = Regex("§.")

object WynnBombAlert : ClientModInitializer {

    val LOGGER = LoggerFactory.getLogger("WynnBombAlert")

    override fun onInitializeClient() {
        LOGGER.info("WynnBombAlert loading...")
        BombAlertConfig.load()
        BombChatListener.register()
        WebhookCommand.register()
        LOGGER.info("WynnBombAlert loaded!")
    }
}
