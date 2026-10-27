package com.colabmcp.core.tunnel

import com.colabmcp.core.util.Log
import com.colabmcp.core.util.SecretStore
import com.colabmcp.core.util.stripAnsi
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class TunnelType(val label: String, val needsAccount: Boolean, val stableUrl: Boolean) {
    NONE("Local only (no public URL)", false, true),
    NGROK("ngrok (free account, stable URL)", true, true),
    LOCALHOST_RUN("localhost.run (no account)", false, false),
    SERVEO("serveo.net (no account)", false, false),
    PINGGY("Pinggy (no account, 60 min)", false, false),
    CUSTOM("My own URL (Cloudflare, Tailscale...)", false, true);

    companion object {
        fun parse(v: String?): TunnelType = when (v?.lowercase()?.replace("_", ".")) {
            null, "", "none", "local" -> NONE
            "ngrok" -> NGROK
            "localhost.run", "lhr" -> LOCALHOST_RUN
            "serveo", "serveo.net" -> SERVEO
            "pinggy" -> PINGGY
            "custom", "manual" -> CUSTOM
            else -> runCatching { valueOf(v.uppercase()) }.getOrDefault(NONE)
        }
    }
}

/**
 * @param token ngrok authtoken (NGROK) or Pinggy token (PINGGY, optional)
 * @param domain ngrok reserved/static domain (NGROK) or requested subdomain (SERVEO)
 * @param customUrl public base URL that already forwards to the local port (CUSTOM)
 */
data class TunnelConfig(
    val type: TunnelType,
    val token: String? = null,
    val domain: String? = null,
    val customUrl: String? = null,
) {
    companion object {
        fun fromArgs(type: String, token: String?, domain: String?, url: String?) =
            TunnelConfig(TunnelType.parse(type), token?.trim(), domain?.trim()?.removePrefix("https://")?.trimEnd('/'), url?.trim())
    }
}

interface Tunnel {
    /** Starts forwarding to the local port and returns the public https base URL. */
    fun start(localPort: Int): String

    /** Blocks until the tunnel stops working. */
    fun awaitDeath()

    fun stop()
}

class TunnelException(message: String) : Exception(message)

// ---------------------------------------------------------------------------- ngrok

class NgrokTunnel(private val authtoken: String, private val domain: String?) : Tunnel {
    private var session: com.ngrok.Session? = null
    private var forwarder: com.ngrok.Forwarder.Endpoint? = null
    private val dead = CountDownLatch(1)

    override fun start(localPort: Int): String {
        if (authtoken.isBlank()) throw TunnelException("Enter your ngrok authtoken (free at dashboard.ngrok.com) first.")
        val s = try {
            com.ngrok.Session.withAuthtoken(authtoken)
                .addClientInfo("colab-mcp-bridge", "1.0")
                .heartbeatHandler(object : com.ngrok.Session.HeartbeatHandler {
                    override fun heartbeat(durationMs: Long) {}
                    override fun timeout() {
                        Log.w("ngrok", "Heartbeat timeout")
                        dead.countDown()
                    }
                })
                .stopCallback { dead.countDown() }
                .restartCallback { dead.countDown() }
                .connect()
        } catch (e: LinkageError) {
            throw TunnelException("ngrok is not available on this device's CPU type (${System.getProperty("os.arch")}). Choose another tunnel.")
        }
        session = s
        val builder = s.httpEndpoint()
        if (!domain.isNullOrBlank()) builder.domain(domain)
        val fwd = builder.forward(URL("http://127.0.0.1:$localPort"))
        forwarder = fwd
        Thread({
            try {
                fwd.join()
            } catch (_: Exception) {
            }
            dead.countDown()
        }, "ngrok-join").apply { isDaemon = true }.start()
        return fwd.url.replaceFirst("http://", "https://")
    }

    override fun awaitDeath() = dead.await()

    override fun stop() {
        runCatching { forwarder?.close() }
        runCatching { session?.close() }
        dead.countDown()
    }
}

// ---------------------------------------------------------------------------- SSH reverse tunnels

/** Reverse SSH tunnel to a public forwarding service (pure Java via JSch, no binaries). */
class SshTunnel(
    private val type: TunnelType,
    private val subdomain: String?,
    private val token: String?,
    private val keyStore: SecretStore?,
) : Tunnel {
    private var session: com.jcraft.jsch.Session? = null
    private var channel: ChannelShell? = null
    @Volatile private var stopped = false

    /** Lines printed by the service (e.g. Serveo's key-registration link). */
    @Volatile var serviceMessage: String? = null
        private set

    private data class Target(val user: String, val host: String, val port: Int, val bind: String, val remotePort: Int, val urlPattern: Regex)

    companion object {
        /** Finds the tunnel address in a service's (possibly colored) console output. */
        internal fun findUrl(pattern: Regex, output: String): String? =
            pattern.find(stripAnsi(output))?.value?.trim()?.trimEnd('.', ',', ';', ')')

        fun findUrl(type: TunnelType, output: String): String? = findUrl(SshTunnel(type, null, null, null).target().urlPattern, output)
    }

    private fun target(): Target = when (type) {
        // Patterns only match the tunnel's own address, not other links in the services' banners
        // (admin.localhost.run, dashboard.pinggy.io, console.serveo.net...).
        TunnelType.LOCALHOST_RUN -> Target("nokey", "localhost.run", 22, "", 80, Regex("https://[a-z0-9-]+\\.lhr\\.(life|rocks|pro)"))
        TunnelType.SERVEO -> Target("colab-mcp", "serveo.net", 22, subdomain?.takeIf { it.isNotBlank() } ?: "", 80, Regex("(?<=Forwarding HTTP traffic from )https://\\S+"))
        TunnelType.PINGGY -> if (token.isNullOrBlank()) {
            Target("free", "free.pinggy.io", 443, "", 0, Regex("https://[a-z0-9.-]+\\.pinggy\\.(link|online)"))
        } else {
            Target(token, "pro.pinggy.io", 443, "", 0, Regex("https://[a-z0-9.-]+\\.pinggy\\.(link|online)"))
        }
        else -> throw IllegalArgumentException("Not an SSH tunnel: $type")
    }

    private fun identity(jsch: JSch) {
        val store = keyStore ?: return
        var prv = store.get("ssh_key_private")
        var pub = store.get("ssh_key_public")
        if (prv == null || pub == null) {
            val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
            val p = ByteArrayOutputStream().also { kp.writePrivateKey(it) }
            val q = ByteArrayOutputStream().also { kp.writePublicKey(it, "colab-mcp-bridge") }
            prv = Base64.getEncoder().encodeToString(p.toByteArray())
            pub = Base64.getEncoder().encodeToString(q.toByteArray())
            store.put("ssh_key_private", prv)
            store.put("ssh_key_public", pub)
            kp.dispose()
        }
        jsch.addIdentity("colab-mcp-bridge", Base64.getDecoder().decode(prv), Base64.getDecoder().decode(pub), null)
    }

    override fun start(localPort: Int): String {
        val t = target()
        val jsch = JSch()
        // localhost.run's free tier is keyless; other services may use our key (Serveo subdomains need it).
        if (type != TunnelType.LOCALHOST_RUN) identity(jsch)
        val s = jsch.getSession(t.user, t.host, t.port)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password,none")
        s.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = ""
            override fun promptPassword(message: String?) = true
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = true
            override fun showMessage(message: String?) {
                message?.let { serviceMessage = it }
            }

            override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?): Array<String> =
                Array(prompt?.size ?: 0) { "" }
        }
        s.setServerAliveInterval(20_000)
        s.setServerAliveCountMax(3)
        s.timeout = 0
        s.connect(30_000)
        session = s
        if (t.bind.isNotEmpty()) {
            s.setPortForwardingR(t.bind, t.remotePort, "127.0.0.1", localPort)
        } else {
            s.setPortForwardingR(t.remotePort, "127.0.0.1", localPort)
        }
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPty(false)
        val input = ch.inputStream
        ch.connect(15_000)
        channel = ch
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + 40_000
        val bytes = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val n = input.read(bytes)
                if (n < 0) break
                buf.append(String(bytes, 0, n))
                findUrl(t.urlPattern, buf.toString())?.let { url ->
                    serviceMessage = stripAnsi(buf.toString()).trim().takeLast(500)
                    drain(input)
                    return url
                }
            } else {
                if (!ch.isConnected || ch.isClosed) break
                Thread.sleep(200)
            }
        }
        serviceMessage = stripAnsi(buf.toString()).trim().takeLast(500)
        stop()
        throw TunnelException("${t.host} did not return a URL. ${serviceMessage?.take(300) ?: ""}".trim())
    }

    /** Keeps reading the service output so its buffer never fills up. */
    private fun drain(input: java.io.InputStream) {
        Thread({
            val b = ByteArray(4096)
            try {
                while (!stopped && input.read(b) >= 0) {
                    // Discard; services print request logs here.
                }
            } catch (_: Exception) {
            }
        }, "ssh-drain").apply { isDaemon = true }.start()
    }

    override fun awaitDeath() {
        while (!stopped) {
            val s = session ?: return
            if (!s.isConnected || channel?.isClosed == true) return
            Thread.sleep(3000)
        }
    }

    override fun stop() {
        stopped = true
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
    }
}

// ---------------------------------------------------------------------------- custom / none

class FixedUrlTunnel(private val url: String) : Tunnel {
    private val latch = CountDownLatch(1)

    override fun start(localPort: Int): String {
        val u = url.trim().trimEnd('/')
        if (!u.startsWith("https://") && !u.startsWith("http://")) throw TunnelException("The custom URL must start with https://")
        return u.removeSuffix("/mcp")
    }

    override fun awaitDeath() = latch.await()
    override fun stop() = latch.countDown()
}

// ---------------------------------------------------------------------------- supervisor

data class TunnelStatus(val state: State, val url: String?, val message: String?) {
    enum class State { OFF, CONNECTING, ONLINE, ERROR }
}

/** Keeps a tunnel up: reconnects with backoff when it drops or the network changes. */
class TunnelSupervisor(
    private val config: TunnelConfig,
    private val localPort: Int,
    private val keyStore: SecretStore?,
    private val onUpdate: (TunnelStatus) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var current: Tunnel? = null
    private var thread: Thread? = null
    private val wake = Object()

    fun start() {
        if (config.type == TunnelType.NONE) {
            onUpdate(TunnelStatus(TunnelStatus.State.OFF, null, "No tunnel: the MCP server is only reachable on this device/network."))
            return
        }
        thread = Thread({ loop() }, "tunnel-supervisor").apply { isDaemon = true; start() }
    }

    private fun create(): Tunnel = when (config.type) {
        TunnelType.NGROK -> NgrokTunnel(config.token ?: "", config.domain)
        TunnelType.LOCALHOST_RUN, TunnelType.SERVEO, TunnelType.PINGGY -> SshTunnel(config.type, config.domain, config.token, keyStore)
        TunnelType.CUSTOM -> FixedUrlTunnel(config.customUrl ?: "")
        TunnelType.NONE -> throw IllegalStateException()
    }

    private fun loop() {
        var backoff = 5_000L
        while (!stopped) {
            onUpdate(TunnelStatus(TunnelStatus.State.CONNECTING, null, "Connecting to ${config.type.label}..."))
            val t = create()
            current = t
            var onlineSince = 0L
            try {
                val url = t.start(localPort)
                onlineSince = System.currentTimeMillis()
                Log.i("Tunnel", "Online: $url")
                onUpdate(TunnelStatus(TunnelStatus.State.ONLINE, url, (t as? SshTunnel)?.serviceMessage?.takeIf { config.type == TunnelType.SERVEO }))
                t.awaitDeath()
                if (stopped) break
                Log.w("Tunnel", "Tunnel dropped; reconnecting")
                onUpdate(TunnelStatus(TunnelStatus.State.ERROR, null, "Tunnel dropped; reconnecting..."))
            } catch (e: Throwable) {
                if (stopped) break
                val msg = (e.message ?: e.javaClass.simpleName).take(400)
                Log.w("Tunnel", "Tunnel failed: $msg")
                onUpdate(TunnelStatus(TunnelStatus.State.ERROR, null, msg))
                if (e is TunnelException && config.type == TunnelType.CUSTOM) return
            } finally {
                runCatching { t.stop() }
                current = null
            }
            if (onlineSince > 0 && System.currentTimeMillis() - onlineSince > 120_000) backoff = 5_000L
            synchronized(wake) { if (!stopped) wake.wait(backoff) }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** Forces an immediate reconnect (e.g. after a network change). */
    fun reconnectNow() {
        current?.stop()
        synchronized(wake) { wake.notifyAll() }
    }

    fun stop() {
        stopped = true
        current?.stop()
        synchronized(wake) { wake.notifyAll() }
        thread?.join(TimeUnit.SECONDS.toMillis(3))
        onUpdate(TunnelStatus(TunnelStatus.State.OFF, null, null))
    }
}
