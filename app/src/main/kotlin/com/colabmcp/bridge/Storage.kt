package com.colabmcp.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.colabmcp.core.tunnel.TunnelConfig
import com.colabmcp.core.tunnel.TunnelType
import com.colabmcp.core.util.JSON
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.SecretStore
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.str
import com.colabmcp.core.util.toJson
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secrets (Google refresh token, tunnel tokens, OAuth state) encrypted with an Android Keystore AES key. */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val file = File(context.filesDir, "secrets.bin")
    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    companion object {
        private const val ALIAS = "colab_mcp_bridge_secrets"
        private const val TAG = "SecretStore"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val map = HashMap<String, String>()
        if (file.exists()) {
            try {
                val bytes = file.readBytes()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                val o = parseJsonObject(String(cipher.doFinal(bytes, 12, bytes.size - 12)))
                o.keys.forEach { k -> o.str(k)?.let { map[k] = it } }
            } catch (e: Exception) {
                Log.e(TAG, "Could not decrypt stored secrets; starting fresh", e)
            }
        }
        cache = map
        return map
    }

    private fun persist(map: Map<String, String>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSON.encodeToString(JsonObject.serializer(), toJson(map) as JsonObject).toByteArray()
        val out = cipher.iv + cipher.doFinal(plain)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override fun get(key: String): String? = synchronized(lock) { load()[key] }

    override fun put(key: String, value: String?) = synchronized(lock) {
        val map = load()
        if (value == null) map.remove(key) else map[key] = value
        persist(map)
    }
}

/** User preferences. Tokens live in the encrypted [SecretStore]. */
class Settings(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tunnelType: TunnelType
        get() = TunnelType.parse(prefs.getString("tunnel_type", TunnelType.NGROK.name))
        set(v) = prefs.edit().putString("tunnel_type", v.name).apply()

    var ngrokToken: String
        get() = secrets.get("ngrok_token") ?: ""
        set(v) = secrets.put("ngrok_token", v.trim().ifEmpty { null })

    var ngrokDomain: String
        get() = prefs.getString("ngrok_domain", "") ?: ""
        set(v) = prefs.edit().putString("ngrok_domain", v.trim().removePrefix("https://").trimEnd('/')).apply()

    var serveoSubdomain: String
        get() = prefs.getString("serveo_subdomain", "") ?: ""
        set(v) = prefs.edit().putString("serveo_subdomain", v.trim()).apply()

    var pinggyToken: String
        get() = secrets.get("pinggy_token") ?: ""
        set(v) = secrets.put("pinggy_token", v.trim().ifEmpty { null })

    var customUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(v) = prefs.edit().putString("custom_url", v.trim()).apply()

    var port: Int
        get() = prefs.getInt("port", 8765)
        set(v) = prefs.edit().putInt("port", v).apply()

    var bindAll: Boolean
        get() = prefs.getBoolean("bind_all", false)
        set(v) = prefs.edit().putBoolean("bind_all", v).apply()

    var secretUrl: Boolean
        get() = prefs.getBoolean("secret_url", false)
        set(v) = prefs.edit().putBoolean("secret_url", v).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", true)
        set(v) = prefs.edit().putBoolean("start_on_boot", v).apply()

    var keepAlive: Boolean
        get() = prefs.getBoolean("keep_alive", true)
        set(v) = prefs.edit().putBoolean("keep_alive", v).apply()

    var idleStopMinutes: Int
        get() = prefs.getInt("idle_stop_minutes", 60)
        set(v) = prefs.edit().putInt("idle_stop_minutes", v).apply()

    /** True while the user wants the MCP server running (restored after reboot/kill). */
    var desiredRunning: Boolean
        get() = prefs.getBoolean("desired_running", false)
        set(v) {
            prefs.edit().putBoolean("desired_running", v).commit()
        }

    fun tunnelConfig(): TunnelConfig = when (tunnelType) {
        TunnelType.NGROK -> TunnelConfig(TunnelType.NGROK, ngrokToken, ngrokDomain.ifBlank { null })
        TunnelType.SERVEO -> TunnelConfig(TunnelType.SERVEO, null, serveoSubdomain.ifBlank { null })
        TunnelType.PINGGY -> TunnelConfig(TunnelType.PINGGY, pinggyToken.ifBlank { null })
        TunnelType.CUSTOM -> TunnelConfig(TunnelType.CUSTOM, customUrl = customUrl)
        else -> TunnelConfig(tunnelType)
    }
}
