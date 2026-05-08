package org.kyowa.wynnbombalert

import net.fabricmc.api.ClientModInitializer
import org.kyowa.wynnbombalert.commands.WebhookCommand
import org.kyowa.wynnbombalert.config.BombAlertConfig
import org.kyowa.wynnbombalert.features.BombChatListener
import org.slf4j.LoggerFactory

val COLOR_CODE_REGEX = Regex("§.")

fun String.stripPrivateUse(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (Character.getType(cp) != Character.PRIVATE_USE.toInt()) {
            sb.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    return sb.toString()
}

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
