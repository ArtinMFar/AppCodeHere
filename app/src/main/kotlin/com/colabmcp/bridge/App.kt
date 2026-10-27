package com.colabmcp.bridge

import android.app.Application
import com.colabmcp.core.Bridge
import com.colabmcp.core.BridgeConfig
import com.colabmcp.core.BridgeStatus
import com.colabmcp.core.colab.CcuInfo
import com.colabmcp.core.colab.ColabApi
import com.colabmcp.core.colab.ColabManager
import com.colabmcp.core.colab.ColabSession
import com.colabmcp.core.colab.GoogleAuth
import com.colabmcp.core.oauth.OAuthServer
import com.colabmcp.core.util.FileSecretStore
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.Logger
import com.colabmcp.core.util.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** Process-wide singletons shared by the UI and the foreground service. */
object Graph {
    lateinit var app: Application
        private set

    val store: SecretStore by lazy {
        // Real devices always have the Android Keystore; plain JVM test environments do not.
        if (runCatching { java.security.KeyStore.getInstance("AndroidKeyStore") }.isSuccess) {
            KeystoreSecretStore(app)
        } else {
            FileSecretStore(File(app.filesDir, "secrets.json"))
        }
    }
    val settings by lazy { Settings(app, store) }
    val auth by lazy { GoogleAuth(store).also { it.onChange = { refreshAccount() } } }
    val api by lazy { ColabApi(auth) }
    val manager by lazy {
        ColabManager(auth, api, File(app.filesDir, "sessions.json")).also {
            it.keepAlive = settings.keepAlive
            it.idleStopMinutes = settings.idleStopMinutes
            it.onChange = { sessions.value = it.list() }
            it.onActionRequired = { title, url -> Notifications.actionRequired(app, title, url) }
        }
    }

    @Volatile var bridge: Bridge? = null

    data class AccountInfo(val email: String?, val ccu: CcuInfo?, val error: String?)

    val status = MutableStateFlow<BridgeStatus?>(null)
    val account = MutableStateFlow<AccountInfo?>(null)
    val sessions = MutableStateFlow<List<ColabSession>>(emptyList())
    val pin = MutableStateFlow("")
    val clients = MutableStateFlow<List<OAuthServer.Client>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val busy = MutableStateFlow<String?>(null)

    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(application: Application) {
        app = application
        Log.sink = object : Logger {
            override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
                val prio = when (level) {
                    Logger.Level.DEBUG -> android.util.Log.DEBUG
                    Logger.Level.INFO -> android.util.Log.INFO
                    Logger.Level.WARN -> android.util.Log.WARN
                    Logger.Level.ERROR -> android.util.Log.ERROR
                }
                android.util.Log.println(prio, "ColabMcp/$tag", message + (error?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""))
                if (level == Logger.Level.DEBUG) return
                val line = "${timeFmt.format(Date())} ${level.name.first()} $tag: $message" + (error?.message?.let { " ($it)" } ?: "")
                synchronized(logLines) {
                    logLines.addLast(line)
                    while (logLines.size > 300) logLines.removeFirst()
                    logs.value = logLines.toList()
                }
            }
        }
        Notifications.ensureChannels(app)
        account.value = AccountInfo(auth.account?.email, null, null)
    }

    /** Refreshes the account email, compute-unit balance and session list in the background. */
    fun refreshAccount() {
        thread(name = "refresh") {
            val email = auth.account?.email
            if (email == null) {
                account.value = AccountInfo(null, null, null)
                sessions.value = emptyList()
                return@thread
            }
            try {
                val ccu = api.ccuInfo()
                account.value = AccountInfo(email, ccu, null)
                sessions.value = manager.sync()
            } catch (e: Exception) {
                account.value = AccountInfo(email, account.value?.ccu, e.message)
            }
        }
    }

    fun createBridge(): Bridge {
        val s = settings
        manager.keepAlive = s.keepAlive
        manager.idleStopMinutes = s.idleStopMinutes
        val b = Bridge(
            auth, manager, store,
            BridgeConfig(
                port = s.port,
                bindAll = s.bindAll,
                tunnel = s.tunnelConfig(),
                dataDir = app.filesDir,
                secretUrlEnabled = s.secretUrl,
            ),
        )
        b.oauth.onChange = {
            pin.value = b.oauth.currentPin()
            clients.value = b.oauth.clients()
        }
        pin.value = b.oauth.currentPin()
        clients.value = b.oauth.clients()
        return b
    }

    /** OAuth clients (connected apps) can be listed/revoked even while the server is stopped. */
    fun revokeAllClients() {
        (bridge?.oauth ?: OAuthServer(store)).revokeAll()
        clients.value = emptyList()
        bridge?.let { pin.value = it.oauth.currentPin() }
    }

    fun loadClients() {
        clients.value = (bridge?.oauth ?: OAuthServer(store)).clients()
    }
}
