package com.colabmcp.bridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.colabmcp.core.colab.GoogleAuth
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val login by lazy { LoopbackLogin(Graph.auth) }
    private val main = Handler(Looper.getMainLooper())
    private var pendingManualLogin: GoogleAuth.PendingLogin? = null
    private var adcToSave: String? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val saveAdc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = adcToSave ?: return@registerForActivityResult
        if (uri != null) {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                .onSuccess { toast("Saved application_default_credentials.json") }
                .onFailure { toast("Save failed: ${it.message}") }
        }
        adcToSave = null
    }
    private val importAdcFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull() ?: return@registerForActivityResult
        importAdc(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen(this)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        Graph.refreshAccount()
        Graph.loadClients()
    }

    override fun onDestroy() {
        login.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions used by the UI

    fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    fun background(label: String, block: () -> Unit) {
        Graph.busy.value = label
        thread(name = "ui-task") {
            try {
                block()
            } catch (e: Exception) {
                toast(e.message ?: e.javaClass.simpleName)
            } finally {
                Graph.busy.value = null
            }
        }
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            copy("Link", url)
            toast("No browser found; link copied")
        }
    }

    fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) toast("$label copied")
    }

    fun share(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, subject))
    }

    fun signInWithBrowser() {
        try {
            val url = login.start { result ->
                result.onSuccess {
                    toast("Signed in as ${it.email}")
                    Graph.refreshAccount()
                    main.post { bringToFront() }
                }.onFailure { toast(it.message ?: "Sign-in failed") }
            }
            openUrl(url)
        } catch (e: Exception) {
            toast("Could not start sign-in: ${e.message}")
        }
    }

    private fun bringToFront() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Copy/paste flow (same as `gcloud auth application-default login --no-browser`). */
    fun beginManualSignIn() {
        val pending = Graph.auth.beginLogin(GoogleAuth.REMOTE_REDIRECT_URI)
        pendingManualLogin = pending
        openUrl(pending.url)
    }

    fun manualSignInUrl(): String? = pendingManualLogin?.url

    fun completeManualSignIn(code: String) {
        val pending = pendingManualLogin ?: run {
            toast("Tap 'Open Google sign-in' first")
            return
        }
        background("Signing in...") {
            val account = Graph.auth.completeLogin(pending, code)
            pendingManualLogin = null
            toast("Signed in as ${account.email}")
            Graph.refreshAccount()
        }
    }

    fun importAdc(json: String) = background("Importing credentials...") {
        val a = Graph.auth.importAdc(json)
        toast("Imported credentials for ${a.email}")
        Graph.refreshAccount()
    }

    fun pickAdcFile() = importAdcFile.launch(arrayOf("application/json", "text/plain", "*/*"))

    fun saveAdcFile(json: String) {
        adcToSave = json
        saveAdc.launch("application_default_credentials.json")
    }

    fun signOut() = background("Signing out...") {
        if (Graph.bridge != null) main.post { BridgeService.stop(this) }
        Graph.auth.signOut()
        Graph.refreshAccount()
    }

    fun startServer() {
        if (!Graph.auth.isSignedIn) {
            toast("Sign in with Google first")
            return
        }
        BridgeService.start(this)
    }

    fun stopServer() = BridgeService.stop(this)

    fun restartServer() {
        BridgeService.stop(this)
        main.postDelayed({ BridgeService.start(this) }, 1500)
    }

    fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Suppress("BatteryLife")
    fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openAppSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}

private val Orange = Color(0xFFF9AB00)
private val DeepOrange = Color(0xFFE8710A)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Orange, secondary = DeepOrange)
        else -> lightColorScheme(primary = DeepOrange, secondary = Orange)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
