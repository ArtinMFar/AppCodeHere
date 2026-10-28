package com.colabmcp.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colabmcp.core.BridgeStatus
import com.colabmcp.core.colab.Hardware
import com.colabmcp.core.tunnel.TunnelStatus
import com.colabmcp.core.tunnel.TunnelType

private const val GEMINI_URL = "https://gemini.google.com/app"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(a: MainActivity) {
    val status by Graph.status.collectAsStateWithLifecycle()
    val busy by Graph.busy.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(title = { Text("Colab MCP Bridge", fontWeight = FontWeight.SemiBold) })
                if (busy != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(busy ?: "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AccountCard(a) }
            item { ServerCard(a, status) }
            item { GeminiHelpCard(a) }
            item { TunnelCard(a, status) }
            item { ColabCard(a) }
            item { ReliabilityCard(a) }
            item { LogCard(a) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StatusDot(color: Color) = Box(Modifier.size(12.dp).background(color, CircleShape))

// ---------------------------------------------------------------------------- account

@Composable
private fun AccountCard(a: MainActivity) {
    val account by Graph.account.collectAsStateWithLifecycle()
    var showPaste by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showAdc by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Section("Google account") {
        val email = account?.email
        if (email == null) {
            Muted("Sign in with the Google account you use for Colab. The app gets the same access as the Colab CLI (Application Default Credentials).")
            Button(onClick = { a.signInWithBrowser() }, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPaste = true }) { Text("Paste a code") }
                OutlinedButton(onClick = { showImport = true }) { Text("Import ADC") }
            }
        } else {
            Text(email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            account?.ccu?.let { Text("%.2f compute units · using %.2f/hour".format(it.balance, it.ratePerHour)) }
            account?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { showAdc = true }) { Text("Show ADC") }
                OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
                IconButton(onClick = { Graph.refreshAccount() }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
    }

    if (showPaste) PasteCodeDialog(a) { showPaste = false }
    if (showImport) ImportAdcDialog(a) { showImport = false }
    if (showAdc) AdcDialog(a) { showAdc = false }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This stops the MCP server and revokes the Google refresh token stored on this phone (and any ADC copies of it).") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; a.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PasteCodeDialog(a: MainActivity, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in with a code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Open Google sign-in (you can also copy the link to another device).\n2. Approve, then copy the code Google shows.\n3. Paste it here.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { a.beginManualSignIn() }) { Text("Open Google sign-in") }
                    TextButton(onClick = {
                        if (a.manualSignInUrl() == null) a.beginManualSignIn()
                        a.manualSignInUrl()?.let { a.copy("Sign-in link", it) }
                    }) { Text("Copy link") }
                }
                OutlinedTextField(code, { code = it }, label = { Text("Verification code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = code.isNotBlank(), onClick = { a.completeManualSignIn(code.trim()); onDismiss() }) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportAdcDialog(a: MainActivity, onDismiss: () -> Unit) {
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import ADC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Paste application_default_credentials.json from `gcloud auth application-default login --scopes=openid,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/userinfo.email,https://www.googleapis.com/auth/colaboratory`.")
                OutlinedTextField(json, { json = it }, label = { Text("ADC JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp))
                TextButton(onClick = { a.pickAdcFile(); onDismiss() }) { Text("Choose a file instead") }
            }
        },
        confirmButton = { TextButton(enabled = json.isNotBlank(), onClick = { a.importAdc(json); onDismiss() }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AdcDialog(a: MainActivity, onDismiss: () -> Unit) {
    val json = remember { Graph.auth.exportAdcJson() ?: "" }
    var reveal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Application Default Credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("This is an authorized_user ADC file for your Google account (same format as gcloud's). Anyone with it can use your Google Cloud and Colab access, so keep it private. Use it with `colab --auth=adc` or GOOGLE_APPLICATION_CREDENTIALS.")
                if (reveal) {
                    SelectionContainer {
                        Text(
                            json, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    TextButton(onClick = { reveal = true }) { Text("Reveal") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { a.copy("ADC JSON", json) }) { Text("Copy") }
                    TextButton(onClick = { a.share("application_default_credentials.json", json) }) { Text("Share") }
                    TextButton(onClick = { a.saveAdcFile(json) }) { Text("Save file") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------------------------------------------------------------------- MCP server

@Composable
private fun ServerCard(a: MainActivity, status: BridgeStatus?) {
    val pin by Graph.pin.collectAsStateWithLifecycle()
    val clients by Graph.clients.collectAsStateWithLifecycle()
    val account by Graph.account.collectAsStateWithLifecycle()
    var confirmRevoke by remember { mutableStateOf(false) }
    val running = status?.running == true
    val tunnel = status?.tunnel

    Section("MCP server") {
        val (color, label) = when {
            !running -> MaterialTheme.colorScheme.outline to "Stopped"
            tunnel?.state == TunnelStatus.State.ONLINE -> Color(0xFF34A853) to "Online"
            tunnel?.state == TunnelStatus.State.CONNECTING -> Color(0xFFF9AB00) to "Connecting tunnel..."
            tunnel?.state == TunnelStatus.State.ERROR -> MaterialTheme.colorScheme.error to "Tunnel error (retrying)"
            else -> Color(0xFF4285F4) to "Running (local only)"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(color)
            Text(label, fontWeight = FontWeight.Medium)
        }
        tunnel?.message?.takeIf { running && tunnel.state != TunnelStatus.State.ONLINE }?.let { Muted(it) }

        if (running) {
            Button(
                onClick = { a.stopServer() }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Stop MCP") }
        } else {
            Button(onClick = { a.startServer() }, enabled = account?.email != null, modifier = Modifier.fillMaxWidth()) { Text("Start MCP") }
            if (account?.email == null) Muted("Sign in with Google first.")
        }

        val mcpUrl = status?.mcpUrl
        if (running) {
            HorizontalDivider()
            Text("MCP link for Gemini", style = MaterialTheme.typography.labelLarge)
            if (mcpUrl != null) {
                SelectionContainer { Text(mcpUrl, fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { a.copy("MCP URL", mcpUrl) }) { Text("Copy") }
                    OutlinedButton(onClick = { a.share("Colab MCP URL", mcpUrl) }) { Text("Share") }
                    OutlinedButton(onClick = { a.openUrl(GEMINI_URL) }) { Text("Open Gemini") }
                }
            } else {
                Muted(if (tunnel?.state == TunnelStatus.State.OFF) "No public tunnel selected. Local URL: ${status.localUrl}/mcp" else "Waiting for the tunnel...")
            }
            status.secretUrl?.let {
                Text("No-login URL (keep secret)", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                TextButton(onClick = { a.copy("Secret MCP URL", it) }) { Text("Copy no-login URL") }
            }
            HorizontalDivider()
            Text("Approval code", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pin.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { Graph.bridge?.oauth?.regeneratePin() }) { Text("New code") }
            }
            Muted("When Gemini (or another MCP client) connects, a page asks for this code. It changes after every successful connection.")
            if (status.toolCalls > 0) Muted("Tool calls: ${status.toolCalls} · last: ${status.lastTool}")
        }

        HorizontalDivider()
        Text("Connected apps: ${clients.count { it.lastUsed > 0 }}", style = MaterialTheme.typography.labelLarge)
        clients.filter { it.lastUsed > 0 }.takeLast(5).forEach {
            Muted("• ${it.name} (last used ${android.text.format.DateUtils.getRelativeTimeSpanString(it.lastUsed)})")
        }
        if (clients.isNotEmpty()) TextButton(onClick = { confirmRevoke = true }) { Text("Disconnect all apps") }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Disconnect all apps?") },
            text = { Text("Every MCP client (including Gemini) loses access and has to be approved again with a new code.") },
            confirmButton = { TextButton(onClick = { confirmRevoke = false; Graph.revokeAllClients() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GeminiHelpCard(a: MainActivity) {
    var expanded by remember { mutableStateOf(false) }
    Section("Connect to Gemini") {
        if (!expanded) {
            TextButton(onClick = { expanded = true }) { Text("Show steps") }
            return@Section
        }
        Text(
            """
            1. Tap Start MCP and wait until the status is Online. Copy the MCP link.
            2. In a browser (on this phone or a computer), open gemini.google.com > Settings > Connected Apps (it may be under Personal Intelligence) > Custom apps > Add a custom app.
            3. Paste the MCP link and tap Next. A page opens asking for the approval code (with ngrok, first tap "Visit Site" on ngrok's warning page): enter the code shown above and tap Allow.
            4. In the Gemini app or web, type @ and pick the app, e.g. "@Colab create a T4 GPU session and show nvidia-smi".

            Requirements (Google): personal Google account, 18+, US, English, Keep Activity on. Use the ngrok tunnel with a free static domain so the link never changes; otherwise you must re-add the app whenever the link changes.
            """.trimIndent(),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { a.openUrl(GEMINI_URL) }) { Text("Open Gemini") }
            TextButton(onClick = { a.openUrl("https://support.google.com/gemini/answer/17209137") }) { Text("Google help") }
        }
    }
}

// ---------------------------------------------------------------------------- tunnel

@Composable
private fun TunnelCard(a: MainActivity, status: BridgeStatus?) {
    val s = Graph.settings
    var type by remember { mutableStateOf(s.tunnelType) }
    var ngrokToken by remember { mutableStateOf(s.ngrokToken) }
    var ngrokDomain by remember { mutableStateOf(s.ngrokDomain) }
    var serveoSub by remember { mutableStateOf(s.serveoSubdomain) }
    var pinggyToken by remember { mutableStateOf(s.pinggyToken) }
    var customUrl by remember { mutableStateOf(s.customUrl) }
    var port by remember { mutableStateOf(s.port.toString()) }
    var bindAll by remember { mutableStateOf(s.bindAll) }
    var secretUrl by remember { mutableStateOf(s.secretUrl) }
    var showToken by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    fun save() {
        s.tunnelType = type
        s.ngrokToken = ngrokToken
        s.ngrokDomain = ngrokDomain
        s.serveoSubdomain = serveoSub
        s.pinggyToken = pinggyToken
        s.customUrl = customUrl
        s.port = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8765
        s.bindAll = bindAll
        s.secretUrl = secretUrl
        dirty = false
    }

    Section("Public link (tunnel)") {
        Muted("Gemini reaches the MCP server on your phone through a public HTTPS tunnel.")
        TunnelType.entries.forEach { t ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = type == t, role = Role.RadioButton, onClick = { type = t; dirty = true }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = type == t, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(t.label)
            }
        }
        when (type) {
            TunnelType.NGROK -> {
                OutlinedTextField(
                    ngrokToken, { ngrokToken = it; dirty = true }, label = { Text("ngrok authtoken") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                )
                OutlinedTextField(ngrokDomain, { ngrokDomain = it; dirty = true }, label = { Text("Domain (optional), e.g. name.ngrok-free.app") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Muted("Free: sign up at ngrok.com, copy your authtoken, and create your free dev domain once under Domains (required). Leave the domain field empty to use it; the MCP link then never changes.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { a.openUrl("https://dashboard.ngrok.com/get-started/your-authtoken") }) { Text("Get authtoken") }
                    TextButton(onClick = { a.openUrl("https://dashboard.ngrok.com/domains") }) { Text("Get free domain") }
                }
            }
            TunnelType.SERVEO -> {
                OutlinedTextField(serveoSub, { serveoSub = it; dirty = true }, label = { Text("Subdomain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Muted("Without a subdomain the link changes on every connection. Custom subdomains need a one-time key registration link that Serveo shows in the status line.")
            }
            TunnelType.PINGGY -> {
                OutlinedTextField(pinggyToken, { pinggyToken = it; dirty = true }, label = { Text("Pinggy token (optional, Pro)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Muted("Free Pinggy links expire after 60 minutes; the app reconnects automatically but the link changes.")
            }
            TunnelType.LOCALHOST_RUN -> Muted("No account needed, but the link changes when the tunnel reconnects.")
            TunnelType.CUSTOM -> {
                OutlinedTextField(customUrl, { customUrl = it; dirty = true }, label = { Text("Public base URL (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Muted("Point your own tunnel (cloudflared in Termux, Tailscale Funnel...) at http://127.0.0.1:${port.ifBlank { "8765" }}.")
            }
            TunnelType.NONE -> Muted("Only clients on this phone (or your Wi-Fi if LAN access is on) can connect.")
        }
        HorizontalDivider()
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5); dirty = true }, label = { Text("Local port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(160.dp),
        )
        SwitchRow("Allow LAN access", "Listen on all interfaces so devices on your Wi-Fi can connect directly.", bindAll) { bindAll = it; dirty = true }
        SwitchRow("No-login secret URL", "Also serve MCP on an unguessable URL without OAuth, for clients that cannot sign in. Anyone with that URL controls your Colab.", secretUrl) { secretUrl = it; dirty = true }
        if (dirty) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); a.toast("Saved") }) { Text("Save") }
                if (status?.running == true) OutlinedButton(onClick = { save(); a.restartServer() }) { Text("Save & restart") }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Muted(it) }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------- Colab sessions

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColabCard(a: MainActivity) {
    val sessions by Graph.sessions.collectAsStateWithLifecycle()
    val account by Graph.account.collectAsStateWithLifecycle()
    var hardware by remember { mutableStateOf(Hardware.CPU) }
    var highMem by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf<String?>(null) }

    Section("Colab runtimes") {
        if (account?.email == null) {
            Muted("Sign in to manage runtimes.")
            return@Section
        }
        if (sessions.isEmpty()) Muted("No active runtimes. Gemini can create them, or create one here.")
        sessions.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.Medium)
                    Muted("${s.hardware.label}${if (s.highMem) " · High-RAM" else ""} · up ${(System.currentTimeMillis() - s.createdAt) / 60000} min")
                }
                IconButton(onClick = { confirmStop = s.name }) { Icon(Icons.Default.Delete, "Stop ${s.name}") }
            }
        }
        HorizontalDivider()
        Text("New runtime", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Hardware.entries.forEach { hw ->
                FilterChip(selected = hardware == hw, onClick = { hardware = hw }, label = { Text(hw.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = highMem, onCheckedChange = { highMem = it }, enabled = !hardware.singleShape)
            Text("High-RAM")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                a.background("Allocating ${hardware.label}...") {
                    val s = Graph.manager.create(null, hardware, highMem)
                    a.toast("Created ${s.name} (${s.hardware.label})")
                    Graph.refreshAccount()
                }
            }) { Text("Create") }
            if (sessions.isNotEmpty()) {
                OutlinedButton(onClick = { confirmStop = "*" }) { Text("Stop all") }
            }
        }
        Muted("Running runtimes use compute units. Runtimes idle longer than the auto-stop time are released automatically.")
    }

    confirmStop?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmStop = null },
            title = { Text(if (name == "*") "Stop all runtimes?" else "Stop $name?") },
            text = { Text("The VM is released and its files under /content are deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = null
                    a.background("Stopping...") {
                        if (name == "*") Graph.manager.stopAll() else Graph.manager.stop(name)
                        Graph.refreshAccount()
                    }
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------- reliability

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReliabilityCard(a: MainActivity) {
    val s = Graph.settings
    var boot by remember { mutableStateOf(s.startOnBoot) }
    var keepAlive by remember { mutableStateOf(s.keepAlive) }
    var idle by remember { mutableStateOf(s.idleStopMinutes) }
    val unrestricted = a.isIgnoringBatteryOptimizations()

    Section("Keep running in the background") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (unrestricted) Color(0xFF34A853) else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(if (unrestricted) "Battery optimisation is off for this app" else "Battery optimisation may stop the server")
        }
        if (!unrestricted) Button(onClick = { a.requestIgnoreBatteryOptimizations() }) { Text("Allow background running") }
        if (!a.hasNotificationPermission()) OutlinedButton(onClick = { a.requestNotificationPermission() }) { Text("Allow notifications") }
        SwitchRow("Restart after reboot", "Start the MCP server again after the phone restarts (if it was running).", boot) { boot = it; s.startOnBoot = it }
        SwitchRow("Keep Colab runtimes alive", "Ping runtimes every few minutes so Colab does not reclaim them while idle.", keepAlive) {
            keepAlive = it
            s.keepAlive = it
            Graph.manager.keepAlive = it
        }
        Text("Auto-stop idle runtimes", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Never", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours").forEach { (m, label) ->
                FilterChip(selected = idle == m, onClick = {
                    idle = m
                    s.idleStopMinutes = m
                    Graph.manager.idleStopMinutes = m
                }, label = { Text(label) })
            }
        }
        Muted("Some phones (Xiaomi, Samsung, Huawei, OnePlus...) also need \"Autostart\" or \"Unrestricted\" battery in the app settings.")
        TextButton(onClick = { a.openAppSettings() }) { Text("Open app settings") }
    }
}

// ---------------------------------------------------------------------------- log

@Composable
private fun LogCard(a: MainActivity) {
    val logs by Graph.logs.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Section("Activity log") {
        val shown = if (expanded) logs.takeLast(150) else logs.takeLast(8)
        if (shown.isEmpty()) Muted("Nothing yet.")
        SelectionContainer {
            Text(shown.reversed().joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
            TextButton(onClick = { a.copy("Log", logs.joinToString("\n")) }) { Text("Copy log") }
        }
    }
}
