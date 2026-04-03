package dev.featureflip.android

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe in-memory flag cache with file persistence.
 */
internal class FlagCache(
    clientKey: String,
    cacheDir: File? = null,
) {
    private val lock = ReentrantReadWriteLock()
    private var flags: Map<String, FlagValue> = emptyMap()
    private val json = jacksonObjectMapper()

    private val cacheFile: File = run {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir"), "featureflip")
        val safeKey = clientKey.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        File(dir, "${safeKey}_flags.json")
    }

    fun setAll(newFlags: Map<String, FlagValue>) {
        lock.write {
            flags = newFlags
        }
        persistToDisk()
    }

    fun get(key: String): FlagValue? = lock.read { flags[key] }

    fun all(): Map<String, FlagValue> = lock.read { flags.toMap() }

    fun loadFromDisk() {
        if (!cacheFile.exists()) return
        try {
            val data = cacheFile.readText()
            val loaded: Map<String, FlagValue> = json.readValue(data)
            lock.write { flags = loaded }
        } catch (_: Exception) {
            // Corrupt cache — ignore and start fresh
        }
    }

    private fun persistToDisk() {
        try {
            cacheFile.parentFile?.mkdirs()
            val data = json.writeValueAsString(lock.read { flags })
            cacheFile.writeText(data)
        } catch (_: Exception) {
            // Best-effort persistence
        }
    }
}
