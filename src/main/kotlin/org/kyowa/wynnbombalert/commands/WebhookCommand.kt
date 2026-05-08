package org.kyowa.wynnbombalert.commands

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.text.Text
import org.kyowa.wynnbombalert.config.BombAlertConfig

object WebhookCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("wynnbomb")
                    .then(
                        literal("webhook")
                            .then(
                                argument("url", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val url = StringArgumentType.getString(ctx, "url")
                                        BombAlertConfig.config.webhookUrl = url
                                        BombAlertConfig.save()
                                        ctx.source.sendFeedback(
                                            Text.literal("§aWebhook URL saved! Bomb alerts are now active.")
                                        )
                                        1
                                    }
                            )
                    )
                    .then(
                        literal("status")
                            .executes { ctx ->
                                val url = BombAlertConfig.config.webhookUrl
                                if (url.isBlank()) {
                                    ctx.source.sendFeedback(
                                        Text.literal("§cNo webhook URL set. Use: /wynnbomb webhook <url>")
                                    )
                                } else {
                                    ctx.source.sendFeedback(
                                        Text.literal("§aWebhook active: §f$url")
                                    )
                                }
                                1
                            }
                    )
            )
        }
    }
}
