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
        val type = Character.getType(cp)
        val drop =
            type == Character.PRIVATE_USE.toInt() ||
            type == Character.UNASSIGNED.toInt() ||
            type == Character.SURROGATE.toInt() ||
            cp in 0xE000..0xF8FF ||    // BMP private use area
            cp in 0xFFF0..0xFFFF ||    // Specials / non-characters
            cp in 0xF0000..0x10FFFF    // Supplementary PUA A + B
        if (!drop) sb.appendCodePoint(cp)
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
