package com.andrewtheguy.duocb

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads and saves [DuocbConfig] as JSON in the app's private files directory.
 *
 * Writes go through a temp file and an atomic rename, matching the desktop's
 * save path: a config half-written by a crash or a kill would cost the user
 * their whole trusted-device list, which is only recoverable by re-trading
 * cards with every device.
 */
class ConfigStore(filesDir: File) {
    private val file = File(File(filesDir, "duocb"), "config.json")

    /**
     * What [load] found. The distinction that matters is the last case: a file
     * that exists but cannot be read is *not* the same as no file at all, and
     * treating it as one would hand back an empty config that the next save
     * writes straight over the top of — turning a transient read failure, or a
     * config from a build that is not this one, into a permanently lost
     * trusted-device list.
     */
    sealed interface Outcome {
        data class Loaded(val config: DuocbConfig) : Outcome
        data object Missing : Outcome
        data class Unreadable(val reason: String) : Outcome
    }

    fun load(): Outcome {
        if (!file.exists()) return Outcome.Missing
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return Outcome.Unreadable("Settings could not be read: ${e.message}")
        }
        val config = try {
            DuocbConfig.fromJson(text)
        } catch (e: Exception) {
            return Outcome.Unreadable("Settings are corrupt or from a different version of the app")
        }
        if (config.version != DuocbConfig.CURRENT_VERSION) {
            return Outcome.Unreadable(
                "Settings are version ${config.version}; this app uses version " +
                    "${DuocbConfig.CURRENT_VERSION} and does not convert older ones",
            )
        }
        return Outcome.Loaded(config)
    }

    fun save(config: DuocbConfig): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(config.toJson())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        false
    }

    fun clear() {
        file.delete()
    }
}
