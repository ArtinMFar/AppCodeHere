# Colab MCP Bridge

An Android app that turns your phone into an **MCP server for Google Colab**, so Gemini (as a *custom connected app*)
or any other MCP client can create CPU/GPU/TPU runtimes, run code, read output and plots, manage files and stop
runtimes — without keeping a Colab tab open.

It is a native Kotlin port of the official [Colab CLI](https://github.com/googlecolab/google-colab-cli)
protocol (no Python/Pydroid needed), plus an MCP server, an OAuth server for Gemini, and a public tunnel, all running
in a foreground service on the phone.

```
Gemini ──HTTPS──▶ tunnel (ngrok / localhost.run / serveo / pinggy / your own)
                     │
                     ▼
        phone: foreground service ── MCP (Streamable HTTP) + OAuth 2.1 (DCR, PKCE, approval code)
                     │
                     ▼  Google ADC (same OAuth client and scopes as the Colab CLI)
        colab.research.google.com /tun/m/*  ──▶  Colab VM (Jupyter kernel over WebSocket)
```

## Install

Download `dist/ColabMcpBridge-1.0.4-universal.apk` on the phone and install it (allow "install unknown apps").
Android 8.0+.

## Set up

1. **Sign in with Google** (the Colab account). The browser opens Google's consent page and returns to the app.
   Alternatives: *Paste a code* (copy/paste flow, works from another device) or *Import ADC* (an
   `application_default_credentials.json` made with
   `gcloud auth application-default login --scopes=openid,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/userinfo.email,https://www.googleapis.com/auth/colaboratory`).
   *Show ADC* exports the app's credential as an ADC file (keep it private).
2. **Pick a tunnel** under *Public link*. Recommended: **ngrok** with a free account — paste your
   [authtoken](https://dashboard.ngrok.com/get-started/your-authtoken) and your free
   [static domain](https://dashboard.ngrok.com/domains) so the MCP link never changes. No-account options
   (localhost.run, serveo, Pinggy) work too, but their link changes when they reconnect.
3. Tap **Start MCP**. When the status is *Online*, copy the **MCP link** (`https://…/mcp`).
4. In a browser open **gemini.google.com → Settings → Connected Apps** (may be under *Personal Intelligence*)
   **→ Custom apps → Add a custom app**, paste the link, tap *Next*. A page (behind ngrok's *Visit Site* warning on
   free accounts) asks for the **approval code** shown in
   the app (and in the notification); enter it and tap *Allow*.
5. In Gemini (app or web) type `@` and choose the app, e.g. *"@Colab create a T4 GPU session, check nvidia-smi and
   train a small CNN on MNIST"*.

Google's requirements for custom apps: personal account, 18+, US, English, Keep Activity on.

**Stop MCP** (in the app or the notification) shuts the server and tunnel down. Colab runtimes keep running until
stopped (by Gemini, the app's *Colab runtimes* card, or the idle auto-stop).

## What Gemini can do (MCP tools)

| Tool | What it does |
| --- | --- |
| `colab_account` | Signed-in account, compute-unit balance and burn rate |
| `colab_list_sessions` | Active runtimes, busy/idle, recent jobs |
| `colab_create_session` | New runtime: `CPU`, `T4`, `L4`, `G4`, `A100`, `H100`, `TPU_V5E1`, `TPU_V6E1`, optional High-RAM |
| `colab_stop_session` | Release a runtime (or all) |
| `colab_run_code` | Run Python (persistent kernel, `!`/`%` magics) or bash; returns text, errors and plots as images; long runs return a job id |
| `colab_get_job_output` | Wait for / fetch output of a running job |
| `colab_send_input` | Answer `input()` prompts |
| `colab_interrupt`, `colab_restart_kernel` | Stop running code / reset the kernel |
| `colab_install_packages` | `uv pip install` (pip fallback) |
| `colab_list_files`, `colab_read_file`, `colab_write_file`, `colab_delete_file` | Files under `/content` (images are returned as images) |
| `colab_system_info` | GPU/TPU, RAM, disk, Python/PyTorch versions |
| `colab_mount_drive`, `colab_authenticate_gcp` | Google Drive / Cloud credentials on the VM (returns the authorization link to open) |
| `colab_notebook_url` | Link that opens the Colab web UI attached to the runtime |
| `colab_session_history`, `colab_export_notebook` | Recent cells, or save them as an `.ipynb` (e.g. into Drive) |

## Staying alive in the background

* Foreground service (`specialUse`) with a persistent notification (URL, approval code, Stop/Copy actions)
* Partial wake lock + Wi-Fi low-latency lock while running
* `START_STICKY`, restart alarm when the app is swiped away or the service is killed
* 15-minute watchdog alarm, restart after reboot / app update
* Tunnel supervisor with exponential back-off and immediate reconnect on network changes
* *Allow background running* button (battery-optimisation exemption) — also enable "Autostart"/"Unrestricted"
  on Xiaomi, Samsung, Huawei, OnePlus…
* Colab side: keep-alive pings for runtimes, and optional auto-stop of idle runtimes to save compute units

## Security

* `/mcp` requires OAuth 2.1 bearer tokens. Clients register dynamically (RFC 7591) or via client-ID metadata
  documents, use PKCE, and **every new connection must be approved with the 6-digit code shown on the phone**
  (rotates after each use; 5 wrong tries lock the page for a minute and rotate it).
* *Disconnect all apps* revokes every client and token.
* Google refresh token, tunnel tokens and OAuth state are encrypted with an Android Keystore key.
* The optional *no-login secret URL* bypasses OAuth for clients that cannot do it — treat it like a password.

## Build & test

```bash
./gradlew :app:assembleRelease          # APKs in app/build/outputs/apk/release (universal + per-ABI)
./gradlew :core:test                    # offline unit tests (OAuth, HTTP server, tunnels, formatting)
COLAB_TEST_ADC=~/.config/gcloud/application_default_credentials.json \
  ./gradlew :app:testDebugUnitTest      # Robolectric: UI + the real foreground service against live Colab
```

The same core runs on any x86_64/arm64 JVM, which is how it was tested without an emulator:

```bash
./gradlew :core:fatJar
java -jar core/build/libs/colab-mcp-bridge-jvm.jar import-adc       # or: login
java -jar core/build/libs/colab-mcp-bridge-jvm.jar new --name gpu --hw T4
java -jar core/build/libs/colab-mcp-bridge-jvm.jar exec --session gpu --code 'import torch; print(torch.cuda.get_device_name(0))'
java -jar core/build/libs/colab-mcp-bridge-jvm.jar serve --tunnel ngrok --ngrok-token TOKEN --domain NAME.ngrok-free.app
```

Release APKs are signed with `app/signing/colab-mcp-bridge.jks` when present (git-ignored; override with
`COLAB_MCP_KEYSTORE` / `COLAB_MCP_KEYSTORE_PASSWORD`), otherwise with the debug key. Installing an APK signed with a
different key requires uninstalling the old one first.

## Notes & limitations

* The localhost.run, serveo and Pinggy tunnels were verified end to end (OAuth + MCP tool calls over the public URL)
  by running the bridge on a Colab VM, and so was ngrok (with a free account and its dev domain). ngrok's free
  plan shows a "You are about to visit…" page in browsers: tap *Visit Site* once on the approval page. Gemini's
  server-to-server calls are not affected. Free ngrok accounts allow one connection at a time.
* The ngrok tunnel uses ngrok-java's native library, available for ARM phones (arm64-v8a, armeabi-v7a). On x86
  devices or phones with 16 KB memory pages, use another tunnel.
* Browser-only Colab features (`google.colab.output` widgets, `files.download`) are not available headless; Drive
  mounting and `auth.authenticate_user()` are supported.
* GPU/TPU availability depends on your Colab plan and current capacity.
