package org.kyowa.wynnbombalert.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

data class BombAlertConfigData(
    var webhookUrl: String = ""
)

object BombAlertConfig {
    private val LOGGER = LoggerFactory.getLogger("WynnBombAlert/Config")
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File by lazy {
        FabricLoader.getInstance().configDir.resolve("wynnbombalert.json").toFile()
    }

    var config = BombAlertConfigData()

    fun load() {
        if (configFile.exists()) {
            runCatching {
                config = GSON.fromJson(configFile.readText(), BombAlertConfigData::class.java)
                    ?: BombAlertConfigData()
            }.onFailure { LOGGER.error("Failed to load config", it) }
        } else {
            save()
        }
    }

    fun save() {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(GSON.toJson(config))
        }.onFailure { LOGGER.error("Failed to save config", it) }
    }
}
