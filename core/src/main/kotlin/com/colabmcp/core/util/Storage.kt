package com.colabmcp.core.util

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Key/value storage for secrets (Google refresh token, ngrok token, OAuth state).
 * The Android app backs this with an Android Keystore encrypted file.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Plain JSON file with owner-only permissions. Used by the JVM runner and tests. */
class FileSecretStore(private val file: File) : SecretStore {
    private val lock = Any()

    override fun get(key: String): String? = synchronized(lock) { read()[key] }

    override fun put(key: String, value: String?) = synchronized(lock) {
        val data = read().toMutableMap()
        if (value == null) data.remove(key) else data[key] = value
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), toJson(data) as kotlinx.serialization.json.JsonObject))
        try {
            Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: Exception) {
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun read(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            val obj = parseJsonObject(file.readText())
            obj.keys.mapNotNull { k -> obj.str(k)?.let { k to it } }.toMap()
        } catch (e: Exception) {
            Log.w("FileSecretStore", "Unreadable store ${file.path}", e)
            emptyMap()
        }
    }
}

class MemorySecretStore : SecretStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
