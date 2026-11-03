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
    private var appliedGeneration = 0L
    private var hasApplied = false

    // Serialises file writes. Each one reads the in-memory flags INSIDE this lock, so
    // whichever write runs last persists the newest state, however the writers interleave.
    private val persistLock = Any()
    private val json = jacksonObjectMapper()

    private val cacheFile: File = run {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir"), "featureflip")
        val safeKey = clientKey.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        File(dir, "${safeKey}_flags.json")
    }

    /**
     * Replaces the cached flags, unless [generation] is older than the newest this cache
     * has already applied. The caller tags a write with the context generation its flags
     * were computed for; the write itself runs after the caller has released its own
     * locks, so a write for the previous user can arrive after the next user's, and
     * applying it would leave that user's flags on disk for the next cold start (#4937).
     * Equal generations apply in arrival order.
     */
    fun setAll(newFlags: Map<String, FlagValue>, generation: Long = 0L) {
        val applied = lock.write {
            if (generation < appliedGeneration) {
                false
            } else {
                appliedGeneration = generation
                hasApplied = true
                flags = newFlags
                true
            }
        }
        if (applied) persistToDisk()
    }

    fun get(key: String): FlagValue? = lock.read { flags[key] }

    fun all(): Map<String, FlagValue> = lock.read { flags.toMap() }

    fun loadFromDisk() {
        if (!cacheFile.exists()) return
        try {
            val data = cacheFile.readText()
            val loaded: Map<String, FlagValue> = json.readValue(data)
            // A setAll that landed during the file read is newer than what was read.
            lock.write { if (!hasApplied) flags = loaded }
        } catch (_: Exception) {
            // Corrupt cache — ignore and start fresh
        }
    }

    private fun persistToDisk() {
        try {
            synchronized(persistLock) {
                cacheFile.parentFile?.mkdirs()
                val data = json.writeValueAsString(lock.read { flags })
                cacheFile.writeText(data)
            }
        } catch (_: Exception) {
            // Best-effort persistence
        }
    }
}
