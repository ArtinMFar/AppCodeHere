package com.colabmcp.core.cli

import com.colabmcp.core.Bridge
import com.colabmcp.core.BridgeConfig
import com.colabmcp.core.colab.ColabApi
import com.colabmcp.core.colab.ColabManager
import com.colabmcp.core.colab.GoogleAuth
import com.colabmcp.core.colab.Hardware
import com.colabmcp.core.colab.OutputFormatter
import com.colabmcp.core.tunnel.TunnelConfig
import com.colabmcp.core.util.FileSecretStore
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop/x86_64 runner for the same core the Android app uses. Lets the bridge be
 * tested (or run on a PC) without an emulator.
 */
fun main(args: Array<String>) {
    val home = File(System.getenv("COLAB_MCP_HOME") ?: (System.getProperty("user.home") + "/.config/colab-mcp-bridge"))
    home.mkdirs()
    val store = FileSecretStore(File(home, "secrets.json"))
    val auth = GoogleAuth(store)
    val api = ColabApi(auth)
    val manager = ColabManager(auth, api, File(home, "sessions.json"))
    val cmd = args.firstOrNull() ?: "help"
    val rest = args.drop(1)
    fun opt(name: String): String? = rest.indexOf("--$name").takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }

    when (cmd) {
        "login" -> {
            val pending = auth.beginLogin()
            println("Open this URL, approve, then paste the code here:\n\n${pending.url}\n")
            print("Code: ")
            val code = readlnOrNull() ?: exitProcess(1)
            println("Signed in as ${auth.completeLogin(pending, code).email}")
        }
        "import-adc" -> {
            val path = rest.firstOrNull() ?: (System.getProperty("user.home") + "/.config/gcloud/application_default_credentials.json")
            println("Imported credentials for ${auth.importAdc(File(path).readText()).email}")
        }
        "export-adc" -> println(auth.exportAdcJson() ?: "Not signed in")
        "whoami" -> {
            val a = auth.account ?: run { println("Not signed in"); exitProcess(1) }
            val ccu = api.ccuInfo()
            println("Email: ${a.email}\nCompute units: %.2f (using %.2f/hr), assignments: %d".format(ccu.balance, ccu.ratePerHour, ccu.assignments))
        }
        "sessions" -> manager.sync().forEach { println("${it.name}\t${it.hardware.label}\t${it.endpoint}") }
        "new" -> {
            val s = manager.create(opt("name"), Hardware.parse(opt("hw")), rest.contains("--high-mem"))
            println("Created ${s.name} (${s.hardware.label}) ${s.endpoint}")
        }
        "exec" -> {
            val code = opt("code") ?: generateSequence(::readLine).joinToString("\n")
            val exec = manager.run(opt("session"), code, waitMs = (opt("timeout")?.toLong() ?: 120) * 1000)
            val f = OutputFormatter.format(exec)
            println(f.text)
            if (f.images.isNotEmpty()) println("(${f.images.size} image(s))")
        }
        "stop" -> {
            val name = opt("session") ?: rest.firstOrNull()
            if (name == null || name == "--all") println("Stopped: ${manager.stopAll()}") else manager.stop(name).also { println("Stopped $name") }
        }
        "serve" -> {
            // Tokens can come from the environment so they stay out of process listings and shell history.
            val token = opt("ngrok-token") ?: System.getenv("NGROK_AUTHTOKEN") ?: System.getenv("PINGGY_TOKEN")
            val tunnel = TunnelConfig.fromArgs(opt("tunnel") ?: "none", token, opt("domain"), opt("url"))
            val config = BridgeConfig(
                port = opt("port")?.toInt() ?: 8765,
                bindAll = rest.contains("--bind-all"),
                tunnel = tunnel,
                dataDir = home,
            )
            val bridge = Bridge(auth, manager, store, config)
            bridge.listener = { st -> println("[bridge] ${st.summary()}") }
            bridge.start()
            var shownPin = bridge.oauth.currentPin()
            println("Approval code for new MCP clients: $shownPin")
            bridge.oauth.onChange = {
                val pin = bridge.oauth.currentPin()
                if (pin != shownPin) {
                    shownPin = pin
                    println("Approval code for new MCP clients: $pin")
                }
            }
            Runtime.getRuntime().addShutdownHook(Thread { bridge.stop() })
            Thread.currentThread().join()
        }
        else -> {
            println(
                """
                colab-mcp-bridge (JVM) commands:
                  login                         Sign in to Google (copy/paste code)
                  import-adc [file]             Import gcloud application_default_credentials.json
                  export-adc                    Print the stored credential as ADC JSON
                  whoami                        Show account + compute units
                  sessions                      List Colab sessions
                  new [--name N] [--hw CPU|T4|L4|A100|H100|TPU_V6E1] [--high-mem]
                  exec [--session N] [--code CODE] [--timeout S]   (reads stdin when --code is absent)
                  stop <name>|--all
                  serve [--port 8765] [--bind-all] [--tunnel none|ngrok|localhost.run|serveo|pinggy|custom]
                        [--ngrok-token T] [--domain D] [--url PUBLIC_URL]
                """.trimIndent(),
            )
        }
    }
    if (cmd != "serve") {
        manager.shutdown()
        exitProcess(0)
    }
}
