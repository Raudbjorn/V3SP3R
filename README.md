```
 ██╗   ██╗ ██████╗ ███████╗██████╗ ███████╗██████╗
 ██║   ██║╚════██╗██╔════╝██╔══██╗██╔════╝██╔══██╗
 ██║   ██║ █████╔╝███████╗██████╔╝█████╗  ██████╔╝
 ╚██╗ ██╔╝ ╚═══██╗╚════██║██╔═══╝ ██╔══╝  ██╔══██╗
  ╚████╔╝ ██████╔╝███████║██║     ███████╗██║  ██║
   ╚═══╝  ╚═════╝ ╚══════╝╚═╝     ╚══════╝╚═╝  ╚═╝
```

# V3SP3R — The AI Brain for Your Flipper Zero

> **Talk to your Flipper Zero like it's your partner-in-hacking.** Vesper turns your pocket hacking tool into an AI-powered command center — controlled entirely through natural language from your Android device or smart glasses.

No menus. No manuals. Just natural language prompting.

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-purple.svg)](https://kotlinlang.org)

---

## What this fork adds over upstream

This is **[Raudbjorn/V3SP3R](https://github.com/Raudbjorn/V3SP3R)**, focused on Android, Flipper Zero and Linux host tooling. It retains the upstream application and selectively integrates features from other forks, with additional review fixes for transport ownership, approvals, credentials and data migration.

**Comparison as of 2026-10-22:** upstream [elder-plinius/V3SP3R](https://github.com/elder-plinius/V3SP3R) `main` is at [`53146f0`](https://github.com/elder-plinius/V3SP3R/tree/53146f0ce0420a5924fb8dc83aa3bd9810eaeb85). This fork merged **30 feature PRs** through [`ed69290`](https://github.com/Raudbjorn/V3SP3R/commit/ed69290fc0967564a4468ef2f4ba2b2e7cbfed50). The additions and changes below are relative to that pinned upstream revision; existing upstream features such as chat, Alchemy, FapHub and the audit log are extended rather than new to this fork.

### Device connections and remote control

| Merged change | What changes for the user | PR |
|---|---|---|
| Ordered BLE setup | Waits for MTU negotiation, service discovery, RX subscription and flow-control setup before declaring the device ready; setup failures and deadlines are explicit. | [#13](https://github.com/Raudbjorn/V3SP3R/pull/13) |
| Screen/command RPC routing | Separates unsolicited screen frames from command replies, retains incomplete frames, and bounds response collection so screen traffic cannot keep a command waiting indefinitely. | [#14](https://github.com/Raudbjorn/V3SP3R/pull/14) |
| Android permission and service repair | Separates essential Bluetooth permissions from optional notifications and version-dependent Location permission; starts connected-device services only with a valid permission path. | [#15](https://github.com/Raudbjorn/V3SP3R/pull/15) |
| Live screen mirror | Displays the Flipper's 128×64 framebuffer and orientation on the Device screen, with start/stop, pause retaining the last frame, and lifecycle cleanup. | [#17](https://github.com/Raudbjorn/V3SP3R/pull/17) |
| GUI input and flow control | Acknowledges press/action/release sequences, handles desktop lock state, attempts bounded release cleanup, and validates serial credits without blindly retrying uncertain button presses. | [#20](https://github.com/Raudbjorn/V3SP3R/pull/20) |
| Remote input and app control | Adds typed GUI input plus loader list/close/signal actions. Loader operations require explicitly selected and handshake-confirmed USB CLI mode. | [#21](https://github.com/Raudbjorn/V3SP3R/pull/21) |
| RPC-only firmware routing | Routes mapped device actions through RPC even when remembered firmware capabilities are stale; readiness follows observed traffic in the current connection. | [#27](https://github.com/Raudbjorn/V3SP3R/pull/27) |
| Transport telemetry | Reports current transport routing, RPC probes and failures with distinct request IDs; late results from an old connection cannot overwrite the new session's diagnostics. | [#28](https://github.com/Raudbjorn/V3SP3R/pull/28) |
| Bounded command locking | Adds queue-acquisition deadlines and binary RPC probes; rejects generic raw CLI on RPC connections rather than mixing text into the RPC stream. | [#29](https://github.com/Raudbjorn/V3SP3R/pull/29) |

See [screen mirroring](docs/features/live-screen-mirror.md), [RPC routing](docs/features/rpc-frame-router.md), [GUI input](docs/features/gui-input-flow-control.md) and [remote app actions](docs/features/remote-app-actions.md). USB RPC is the default for file and screen features. To use the constrained CLI actions, disconnect and explicitly select USB CLI mode; reconnect in RPC mode to resume file/screen operations.

### App installation and signal editing

| Merged change | What changes for the user | PR |
|---|---|---|
| Firmware-aware app catalog | Selects FapHub builds using the device's firmware target and SDK API instead of assuming any catalog binary is compatible. | [#3](https://github.com/Raudbjorn/V3SP3R/pull/3) |
| Installed-app and tool validation | Inventories apps in nested directories and rejects invented model tool names before execution. | [#5](https://github.com/Raudbjorn/V3SP3R/pull/5) |
| Verified app installation | Checks ARM ELF payloads and catalog checksums, uploads to a temporary path, reads back the bytes before activation, and preserves existing same-name applications. | [#6](https://github.com/Raudbjorn/V3SP3R/pull/6) |
| SubGHz RAW editing | Imports `RAW_Data` pulse timings into Alchemy layers for waveform visualization, editing and export. | [#8](https://github.com/Raudbjorn/V3SP3R/pull/8) |

See the [RAW editor guide](docs/integrations/subghz-raw-editor.md).

### AI providers and chat

| Merged change | What changes for the user | PR |
|---|---|---|
| Tool-capable model catalog | Filters the live OpenRouter catalog for tool support, keeps unavailable selections visible, and reports blank or malformed responses instead of silently ending a turn. | [#9](https://github.com/Raudbjorn/V3SP3R/pull/9) |
| Separate provider settings | Adds OpenRouter, OpenAI, Gemini, xAI and Anthropic configurations with independent encrypted keys, models, request settings and speech capabilities. | [#10](https://github.com/Raudbjorn/V3SP3R/pull/10) |
| Native Anthropic client | Uses the Messages API with native authentication, images, tool calls and tool results. Anthropic has no speech backend. | [#11](https://github.com/Raudbjorn/V3SP3R/pull/11) |
| Streaming and context handling | Streams text and tool turns with cancellable HTTP calls. Partial tools never execute; retries stop once output begins. Adds Anthropic prompt-cache markers, optional thinking and preservation of native thinking/compaction blocks with bounded history. | [#12](https://github.com/Raudbjorn/V3SP3R/pull/12) |

See [model selection](docs/model-catalog.md), [provider settings](docs/provider-settings.md), [native Anthropic](docs/native-anthropic.md) and [streaming behavior](docs/provider-streaming.md). Provider protocol tests use HTTP fixtures; live account access and current model availability still need checking with the chosen provider.

### Credentials and stored data

| Merged change | What changes for the user | PR |
|---|---|---|
| Durable credential migration | Migrates legacy plaintext API keys only after a synchronous encrypted write and readback, excludes sensitive stores from backup/device transfer, and restricts cleartext traffic. | [#16](https://github.com/Raudbjorn/V3SP3R/pull/16) |
| TLS and diagnostic hardening | Tightens transport and smart-glasses bridge validation and reduces sensitive data in diagnostic logs. | [#18](https://github.com/Raudbjorn/V3SP3R/pull/18) |
| SQLCipher encryption with conversion | Adds Keystore-protected database keys and verified conversion of existing plaintext Room data, including interruption recovery. Missing keys or ambiguous recovery states preserve files and report an error; destructive migration fallback is disabled. | [#19](https://github.com/Raudbjorn/V3SP3R/pull/19) |

Read [database encryption and recovery](docs/database-encryption.md) before upgrading an installation with existing data. Native migration tests have been compiled but still require execution on Android.

### Knowledge, phone BLE and scoped workflows

| Merged change | What changes for the user | PR |
|---|---|---|
| Read-only MCP knowledge server | Exposes documentation search, action descriptions and schemas over stdio, SSE or streamable HTTP. It serves reference material and does not execute Flipper commands. | [#1](https://github.com/Raudbjorn/V3SP3R/pull/1) |
| Generated command contract | Generates the command schema and fails build verification on action/argument drift. The integrated contract covers 58 actions and 52 arguments. | [#22](https://github.com/Raudbjorn/V3SP3R/pull/22) |
| On-demand skills | Loads seven bundled methodology playbooks only when requested and checks their MCP corpus copies for drift. Playbooks do not override transport or approval rules. | [#23](https://github.com/Raudbjorn/V3SP3R/pull/23) |
| Phone-side BLE/GATT tools | Uses the Android phone adapter independently of Flipper to scan, enumerate, read, write and collect notifications. Operations are bounded; writes and subscriptions require explicit approval. | [#24](https://github.com/Raudbjorn/V3SP3R/pull/24) |
| Typed actions and findings | Adds constrained USB CLI device actions, session-scoped findings and audit queries, validation history, heuristic classification and authoring helpers. Findings use additive, data-preserving migrations; firmware support remains action-dependent. | [#25](https://github.com/Raudbjorn/V3SP3R/pull/25) |
| Experimental scoped campaigns | Adds WorkManager phases, exact directory scopes, command/time limits, an approval inbox, expiry and a stop-all switch. Disabled by default; supports a bounded device/storage command subset and records uncertain execution after interruption rather than replaying it. | [#26](https://github.com/Raudbjorn/V3SP3R/pull/26) |

Start with the [MCP setup](mcp-server/README.md), [command schema](docs/ports/command-schema.md), [skills](docs/ports/on-demand-skills.md), [phone BLE tools](docs/ports/phone-ble.md), [typed actions and findings](docs/ports/device-findings.md), or [campaign guide](docs/ports/ralph-campaigns.md). A campaign stop prevents subsequent steps; an already-sent device command may still finish.

### Linux tools and acceptance tests

| Merged change | What changes for the user | PR |
|---|---|---|
| Python terminal | Provides interactive local/file and direct-JSON commands without an API key, with approvals and persisted history/audit. Mock filesystem mode is explicit; the experimental USB CLI adapter never silently falls back to mock and does not implement verified hardware storage/RPC operations. | [#2](https://github.com/Raudbjorn/V3SP3R/pull/2) |
| Linux USB development bridge | Connects a Linux serial device to an Android debug emulator through framed RPC over a loopback socket with a per-process pairing code. Release builds reject this developer transport. | [#7](https://github.com/Raudbjorn/V3SP3R/pull/7) |
| Regression and device acceptance suite | Adds setup, locking, RPC and catalog regressions plus opt-in Android acceptance checks for reads, transfer readback and reconnect. | [#4](https://github.com/Raudbjorn/V3SP3R/pull/4) |
| Hardware-routing regressions | Covers mapped launch/script/transmit routing, RPC/CLI boundaries and rejection of app-override arguments, with a device acceptance checklist. | [#30](https://github.com/Raudbjorn/V3SP3R/pull/30) |

See the [Python terminal](python/README.md), [USB development bridge](docs/integrations/linux-usb-bridge.md), [device acceptance guide](docs/device-acceptance.md) and [hardware-routing checklist](docs/testing/hardware-routing.md).

### Integration validation

Recorded validation for the integrated tree at `ed69290` (2026-10-22):

- Debug APK built; 487 Android JVM tests ran with **485 passed and two intentional live-test skips**.
- Five schema-generator tests and command-schema verification passed.
- 148 MCP tests, Ruff, skill synchronization and an isolated source-distribution-to-wheel installation passed.
- Android instrumentation APK compiled; instrumentation tests were **not executed**.

Physical Android/Flipper BLE and USB acceptance, native SQLCipher migration execution and live credentialed provider calls remain unverified. These results establish automated coverage, not hardware or production acceptance. Campaigns remain experimental and disabled by default; the Linux bridge is for debug/emulator development.

### Port attribution

These are selective ports with integration fixes, not whole-fork merges. Source revisions and licensing notes are retained in the linked feature documentation and PRs. Donors include [ckumar1](https://github.com/ckumar1/V3SP3R), [joylessorchid](https://github.com/joylessorchid/V3SP3R), [ahuray](https://github.com/ahuray/V3SP3R), [charlesreid1](https://github.com/charlesreid1/M0MA-V3SP3R), [Shiva108](https://github.com/Shiva108/V3SP3R), [bob-reis](https://github.com/bob-reis/V3SP3R), [cryptofixyup](https://github.com/cryptofixyup/V3SP3R) and [gattaa](https://github.com/gattaa/V3SP3R_Docker).

---

## Why Vesper?

The Flipper Zero is one of the most versatile hardware hacking tools ever made — but navigating its menus, managing files, and crafting signals by hand is tedious. **Vesper eliminates the friction.** Plug in an AI brain via OpenRouter, connect over Bluetooth, and you have a voice-commanded hardware lab in your pocket.

- **Instant expertise** — Don't memorize SubGHz protocols or IR formats. Just say what you want.
- **Real-time control** — The AI reads your Flipper's state, executes commands, and reports back in seconds.
- **Multimodal input** — Voice commands, photo analysis, and text chat. Use your phone camera or smart glasses to show the AI what you're looking at.
- **Signal alchemy** — Build, layer, and export custom RF waveforms with a visual editor.
- **Smart glasses integration** — Pair with Mentra smart glasses for hands-free, heads-up Flipper control.
- **Safety-first architecture** — Every AI action is risk-classified. Destructive operations require explicit confirmation. System paths are locked by default.

Whether you're a security researcher, a red teamer, a CTF competitor, a hardware tinkerer, or just someone who wants to understand the invisible signals around you — Vesper makes the Flipper Zero *dramatically* more accessible and more powerful.

---

## Features

### Chat — AI-Driven Flipper Control
Talk to your Flipper in plain English (voice or text):
- *"Show me my SubGHz captures"*
- *"What's my battery level?"*
- *"Create a backup of all my IR remotes"*
- *"Generate a BadUSB script that opens a reverse shell"*
- *"Change the frequency in garage.sub to 315MHz"*

### Hardware Control
Direct control over all Flipper subsystems:
- **SubGHz** — Transmit and analyze RF signals
- **Infrared** — Send and record IR commands
- **NFC / RFID / iButton** — Emulate stored credentials
- **BadUSB** — Execute HID attack scripts
- **GPIO / LED / Vibro** — Control hardware peripherals
- **App Launcher** — Start any Flipper app by name

### Multimodal Input
- **Voice input** — Speak commands using on-device speech recognition
- **Photo analysis** — Snap a picture of a remote, a device label, or anything — the AI sees what you see
- **Text-to-speech** — AI responses use the selected provider’s speech backend: OpenRouter audio, OpenAI speech, Gemini TTS, or xAI TTS; Anthropic has no speech endpoint
- **Smart glasses** — Hands-free voice + camera via Mentra glasses bridge

### Ops Center
Built for reliability-obsessed users:
- **Pipeline Health** — BLE/RPC/CLI readiness and diagnostics at a glance
- **Runbooks** — One-tap recovery and smoke-test sequences
- **Live Status** — Transport and command pipeline behavior in one view

### Alchemy Lab — Signal Synthesis
Build custom RF signals from scratch:
- Visual waveform editor with real-time preview
- Layer and fuse multiple signal patterns
- Export directly to your Flipper's SD card

### Payload Lab
AI-powered payload generation:
- BadUSB scripts, SubGHz signals, IR remotes, NFC tags
- Validated before deployment — the AI checks format and safety
- Direct push to Flipper storage

### FapHub Browser
Browse and install Flipper applications:
- Search the Flipper app catalog
- One-tap install to your device

### Resource Browser
Find and download community resources:
- Search GitHub for Flipper-compatible files
- Browse repositories and download directly to your Flipper

### Device Manager
Full Flipper visibility:
- Battery, storage, firmware info, and connection status
- Scan, pair, and manage BLE connections
- Direct file browsing and management

### Risk & Permissions Engine
Every AI action is classified before execution:
- **Low risk** — Read-only ops execute automatically
- **Medium risk** — File writes show a diff for review
- **High risk** — Destructive ops require double-tap confirmation
- **Blocked** — System/firmware paths require explicit unlock

Configure **auto-approve** per risk tier in Settings to move faster when you trust the workflow.

### Audit Log
Every action the AI takes is logged:
- Full history of commands, results, and approvals
- Filterable by action type and session
- Exportable for compliance and review

---

## Recommended AI Models

Vesper supports provider-specific configurations for OpenRouter, OpenAI, Gemini, xAI, Anthropic and MiniMax. Use models with tool support for device commands; see [provider settings](docs/provider-settings.md) for protocol and speech availability. For the default OpenRouter configuration:

| Model | Why Use It | Speed | Cost |
|-------|-----------|-------|------|
| **`nousresearch/hermes-4`** | Outstanding tool-use — purpose-built for agentic workflows. Top pick for power users. | Fast | $$ |
| **`anthropic/claude-opus-4.6`** | Most capable reasoning model. Exceptional at complex multi-step operations and signal analysis. | Medium | $$$$ |
| **`anthropic/claude-sonnet-4`** | Best balance of speed, intelligence, and cost. Great default. | Fast | $$ |
| **`anthropic/claude-haiku-4`** | Blazing fast for simple reads and quick commands. | Fastest | $ |
| **`openai/gpt-4o`** | Strong general-purpose alternative. | Fast | $$ |

**Our recommendation:** Start with **Hermes 4** or **Claude Sonnet 4** for daily use. Reach for **Claude Opus 4.6** when you need deep reasoning.

### MiniMax (direct)

Connect directly to [MiniMax](https://www.minimaxi.com) models without an OpenRouter middleman:

| Model | Why Use It | Context | Speed |
|-------|-----------|---------|-------|
| **`MiniMax-M2.7`** | Latest MiniMax model with strong tool-calling and reasoning. | 1M tokens | Fast |
| **`MiniMax-M2.7-highspeed`** | Lower latency variant of M2.7 — great for quick commands. | 1M tokens | Fastest |
| **`MiniMax-M2.5`** | Previous generation, still very capable. | 204K tokens | Fast |
| **`MiniMax-M2.5-highspeed`** | Fastest MiniMax option, ideal for simple operations. | 204K tokens | Fastest |

Get your MiniMax API key at [platform.minimax.io](https://platform.minimax.io).

---

## Quick Start

### Requirements

| Item | Notes |
|------|-------|
| **Flipper Zero** | [shop.flipperzero.one](https://shop.flipperzero.one) |
| **Android device** | Android 8.0+ (API 26), Bluetooth required |
| **LLM API key** | [OpenRouter](https://openrouter.ai) (200+ models) or [MiniMax](https://platform.minimax.io) (direct) |

### 1. Prep Your Flipper
1. Charge it up (USB-C)
2. Update firmware via [qFlipper](https://flipperzero.one/update) (recommended)
3. Enable Bluetooth: Settings > Bluetooth > ON

### 2. Get an API Key

**Option A — OpenRouter** (recommended for beginners):
1. Sign up at [openrouter.ai](https://openrouter.ai)
2. Go to **Keys** > **Create Key**
3. Copy the key (`sk-or-...`) — you'll paste this into Vesper
4. Add $5-10 in credits to start (most conversations cost pennies)

**Option B — MiniMax** (direct access):
1. Sign up at [platform.minimax.io](https://platform.minimax.io)
2. Create an API key in the console
3. In Vesper Settings, select the **MiniMax** provider and paste the key

### 3. Build & Install

```bash
git clone https://github.com/Raudbjorn/V3SP3R.git
cd V3SP3R
```

Open the project in [Android Studio](https://developer.android.com/studio), let Gradle sync, then:

- **Build > Build APK(s)** or hit the green play button
- APK output: `app/build/outputs/apk/debug/app-debug.apk`

Install via USB debugging or transfer the APK to your phone.

<details>
<summary><strong>Command-line build (no Android Studio)</strong></summary>

```bash
# Requires Android SDK and JDK 17+
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
</details>

### 4. First Launch
1. **Grant permissions** — On Android 12+, allow Nearby devices. On Android 11 and earlier, allow Location for BLE scanning. Notifications are optional.
2. **Add your API key** — Settings > select your provider and enter that provider’s API key (OpenRouter is the default)
3. **Connect** — Device tab > Scan > tap your Flipper
4. **Go** — Chat tab > start talking to your Flipper

---

## Smart Glasses Setup (Optional)

Vesper supports hands-free operation via Mentra smart glasses.

1. Deploy the bridge server from `mentra-bridge/`:
   ```bash
   cd mentra-bridge
   npm install && npm run build && npm start
   ```
2. In Vesper Settings, enable **Smart Glasses** and enter the bridge URL
3. Voice transcriptions from your glasses flow directly to Vesper
4. The AI can request photos through your glasses camera

---

## Architecture

```
┌─────────────────────────────────────────┐
│              Vesper App                  │
├─────────────────────────────────────────┤
│  UI Layer (Jetpack Compose + Hilt)      │
│  ├── Chat Screen (voice, images, text)  │
│  ├── Ops Center                         │
│  ├── Alchemy Lab & Payload Lab          │
│  ├── File Browser & FapHub              │
│  ├── Signal Arsenal & Spectral Oracle   │
│  └── Device & Settings Screens          │
├─────────────────────────────────────────┤
│  Domain Layer                           │
│  ├── VesperAgent (AI orchestration)     │
│  ├── CommandExecutor (risk enforcement) │
│  ├── RiskAssessor + PermissionService   │
│  ├── ForgeEngine (payload generation)   │
│  ├── DiffService + AuditService         │
│  └── Signal Processing                  │
├─────────────────────────────────────────┤
│  Data Layer                             │
│  ├── Provider clients (chat + speech)  │
│  ├── FlipperBleService (BLE transport)  │
│  ├── GlassesIntegration (Mentra bridge) │
│  ├── SQLCipher + Room (stored data)    │
│  └── Encrypted DataStore (settings)     │
└─────────────────────────────────────────┘
```

## Project Structure

```
V3SP3R/
├── app/src/main/java/com/vesper/flipper/
│   ├── ai/                     # AI integration
│   │   ├── OpenRouterClient.kt # OpenRouter LLM API, tool calling, JSON repair
│   │   ├── LlmProvider.kt      # Provider catalog (OpenRouter, Gemini, OpenAI, xAI, Anthropic, MiniMax)
│   │   ├── VesperAgent.kt      # Conversation orchestrator
│   │   ├── VesperPrompts.kt    # System prompts
│   │   ├── PayloadEngine.kt    # Payload generation
│   │   └── FlipperToolExecutor.kt
│   ├── ble/                    # Bluetooth
│   │   ├── FlipperBleService.kt
│   │   ├── FlipperProtocol.kt
│   │   ├── FlipperFileSystem.kt
│   │   └── MarauderBridge.kt
│   ├── glasses/                # Smart glasses
│   │   ├── GlassesIntegration.kt
│   │   └── GlassesBridgeClient.kt
│   ├── voice/                  # Voice I/O
│   │   ├── SpeechRecognitionHelper.kt
│   │   └── ElevenLabsTtsService.kt
│   ├── domain/
│   │   ├── executor/           # Command execution & risk
│   │   ├── model/              # Data models
│   │   ├── service/            # Audit, diff, permissions
│   │   └── protocol/           # SubGHz, Pwnagotchi
│   ├── data/                   # Persistence & settings
│   ├── security/               # Input validation, sanitization
│   ├── ui/                     # Jetpack Compose screens
│   │   ├── screen/
│   │   ├── viewmodel/
│   │   ├── components/
│   │   └── theme/
│   └── widget/                 # Home screen widget
├── mcp-server/                 # Read-only knowledge/schema MCP server
├── knowledge/                  # Reference corpus and synchronized playbooks
├── python/                     # Linux terminal (mock / experimental USB CLI)
├── tools/                      # Debug USB bridge and host tests
├── buildSrc/                   # Command schema generation and verification
├── mentra-bridge/              # Smart glasses bridge server (Node.js)
├── docs/                       # Architecture docs, schemas
└── gradle/                     # Build configuration
```

---

## Supported Actions

Selected actions are listed below. The [generated command schema](docs/execute_command_schema.json) is the complete action/argument reference; transport and approval rules still determine whether an action can run.

| Action | Description | Risk Level |
|--------|-------------|------------|
| `list_directory` | List files and folders | Low |
| `read_file` | Read file contents | Low |
| `write_file` | Create or modify files | Medium |
| `create_directory` | Create folders | Low |
| `delete` | Delete files or folders | High |
| `move` / `rename` / `copy` | File operations | Medium-High |
| `get_device_info` | Battery, firmware, hardware info | Low |
| `get_storage_info` | SD card and internal storage stats | Low |
| `execute_cli` | Generic raw CLI is rejected; use mapped RPC actions or the explicitly selected, confirmed USB CLI mode for supported typed actions | Medium |
| `push_artifact` | Push generated files to Flipper | Medium |
| `forge_payload` | AI-generate SubGHz/IR/BadUSB/NFC payloads | Medium |
| `subghz_transmit` | Transmit SubGHz signal | High |
| `ir_transmit` | Send infrared command | Medium |
| `nfc_emulate` / `rfid_emulate` / `ibutton_emulate` | Emulate credentials | Medium |
| `badusb_execute` | Run HID attack script | High |
| `launch_app` | Start a Flipper app | Medium |
| `led_control` / `vibro_control` | Hardware peripherals | Low |
| `search_faphub` / `install_faphub_app` | Browse & install apps | Medium |
| `browse_repo` / `download_resource` / `github_search` | Find community resources | Low |
| `request_photo` | Capture photo via smart glasses | Low |

---

## Troubleshooting

<details>
<summary><strong>Flipper not found when scanning</strong></summary>

1. On Flipper: Settings > Bluetooth > make sure it's ON
2. Toggle Bluetooth off/on on your phone
3. Make sure Flipper isn't connected to another device (e.g. qFlipper)
4. Move within 3 feet / 1 meter
5. Check Bluetooth/Nearby devices permission on Android 12+, or Location permission and services on Android 11 and earlier
</details>

<details>
<summary><strong>Build failed in Android Studio</strong></summary>

1. Ensure JDK 17+ is installed
2. File > Sync Project with Gradle Files
3. Build > Clean Project > Rebuild Project
4. If still failing: close Android Studio, delete `.gradle` folder, reopen
</details>

<details>
<summary><strong>AI not responding</strong></summary>

1. Verify your API key in Settings (OpenRouter or MiniMax, depending on your selected provider)
2. Check your credit balance at [openrouter.ai](https://openrouter.ai) or [platform.minimax.io](https://platform.minimax.io)
3. Check internet connection
4. Try a different model — some may be temporarily unavailable
5. Try switching providers (Settings > LLM Provider)
</details>

<details>
<summary><strong>"Could not parse tool arguments" errors</strong></summary>

This means the AI model returned malformed JSON. Vesper includes automatic JSON repair, but some models are more reliable than others. Try:
1. Tap **Retry** on the error message
2. Switch to a recommended model (Hermes 4, Claude Sonnet 4)
3. Simplify your request
</details>

<details>
<summary><strong>Permission denied errors</strong></summary>

- Some Flipper paths are protected by default (system files, firmware areas)
- Go to Settings > Permissions to unlock specific paths
- Enable auto-approve per risk tier to move faster
- Blocked paths always require manual unlock
</details>

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a PR.

Areas that need love:
- iOS version (SwiftUI)
- Signal format parsers (new protocols)
- Additional payload templates
- UI/UX improvements
- Translations / i18n
- Test coverage

---

## Security

Found a vulnerability? Please report it responsibly. See [SECURITY.md](SECURITY.md) for details.

---

## Safety & Legal

- Vesper is a tool for **education and legitimate security research**
- Only use on devices you own or have explicit authorization to test
- All AI actions are logged and auditable
- The AI refuses clearly malicious requests
- Destructive operations require explicit user confirmation
- You are responsible for complying with all applicable laws in your jurisdiction

---

## License

AGPL-3.0 — see [LICENSE](LICENSE) for the full text.

---

**V3SP3R** — AI-powered hardware hacking, in your pocket. Your Flipper Zero just got a brain upgrade.
