package org.kyowa.wynnbombalert

import net.fabricmc.api.ClientModInitializer
import org.kyowa.wynnbombalert.commands.WebhookCommand
import org.kyowa.wynnbombalert.config.BombAlertConfig
import org.kyowa.wynnbombalert.features.BombChatListener
import org.slf4j.LoggerFactory

val COLOR_CODE_REGEX = Regex("§.")

// Strips Wynncraft private-use-area glyph characters:
//   U+E000–U+F8FF  (BMP private use area)
//   U+F0000–U+10FFFF (supplementary PUA via surrogate pairs, high surrogate U+DB80–U+DBFF)
val PRIVATE_USE_REGEX = Regex("[-]|[\uDB80-\uDBFF][\uDC00-\uDFFF]")

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
