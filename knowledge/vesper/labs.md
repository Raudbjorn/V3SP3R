# Labs

The **Labs** tab is Vesper's payload-authoring workshop. It has three sub-surfaces:

- **Alchemy Lab** — one screen that generates any Flipper file type from a natural-language prompt, plus a "vault" browser of everything you've captured or crafted.
- **Payload Lab** — a specialized 3-step wizard for the two families where staged generation shines: **BadUSB** scripts and **Evil Portal** HTML pages.
- **Campaigns** — autonomous multi-phase engagements. Covered separately in [`campaigns.md`](campaigns.md).

This doc covers the two payload labs.

---

## When to use which lab

| Use **Alchemy Lab** when… | Use **Payload Lab** when… |
|--------------------------|---------------------------|
| You want any file type: SubGHz, IR, NFC, RFID, iButton, BadUSB | You specifically want a BadUSB script or Evil Portal HTML |
| One-shot generation is fine | You want to see generation → validation → save as separate reviewable steps |
| You want to browse and edit your existing "loot" | You want to attach a photo for the AI to look at first |
| You want inline field editing (tap a value to tweak it) | You want built-in templates to seed from |

Neither lab bypasses Vesper's risk model — everything they write to the Flipper still runs through the same approval flow described below.

---

## The safety flow, in one place

Both labs write files to the Flipper, and Alchemy Lab can transmit / execute what it generates. Every one of those actions is classified into a **risk level** before it runs:

| Risk | What it covers | What you see |
|------|----------------|--------------|
| **LOW** | Reads, scans, device info, safe status queries | Runs silently |
| **MEDIUM** | File writes, mutations with limited blast radius | **Single-tap Approve** — with a diff preview for writes to existing files |
| **HIGH** | Destructive, transmit / execute / emulate / write to protected areas | **Double-tap to confirm** (second tap within 1.8 seconds) |
| **BLOCKED** | Protected system paths (`/int/…`, firmware areas), sensitive extensions (`.key`, `.priv`, `.secret`) | Refused until you unlock the specific path or extension in **Settings → Permissions** |

Two settings under **Settings** can loosen the interactive flow if you trust the workflow you're running:

- **Auto-approve MEDIUM** — MEDIUM actions run without the tap. Diffs are still shown; you just don't have to confirm.
- **Auto-approve HIGH** — HIGH actions run without the double-tap. (Autonomous campaigns are planned separately; see [`campaigns.md`](campaigns.md).)

**Approval boundary:** the AI can author a HIGH-risk payload in the labs. This reference does not guarantee a prompt or human release for every Labs action. `autoApproveHigh` governs actions handled by `CommandExecutor`; direct Labs dispatch is outside that guarantee.

Every action, every approval decision, every result is written to the audit log (**Chat → Audit log**) and is filterable by risk level.

---

## Alchemy Lab

**One screen. Any payload type. Fully AI-forged.**

### How to use it

1. Open **Labs → Alchemy Lab**.
2. Type a natural-language prompt in the Forge box — "Samsung TV power toggle", "generic garage door 315 MHz", "BadUSB that opens Notepad and types 'hi'".
3. Optionally hint the payload type; otherwise it's inferred from the prompt.
4. Tap **Forge**. The AI generates a **blueprint** — a payload split into tappable fields you can edit before deploying.
5. Review, tweak any field (frequency, preset, script body, whatever), and either:
   - **Save to Flipper** — writes to the appropriate `/ext/` directory. Prompts for approval if the write is MEDIUM/HIGH.
   - **Transmit / execute** (for signal types and BadUSB) — sends the payload live. HIGH-risk; double-tap to confirm.

If the AI is unreachable, Alchemy falls back to a hand-authored template of the requested type so you're never fully stuck.

### The Vault

Below the Forge is your **loot inventory** — every file on the Flipper (plus every payload you've crafted), shown as cards with:

- **Type** — SubGHz 📡, IR 🔴, NFC 💳, RFID 🏷, BadUSB 💀, iButton 🔑
- **Rarity** — cosmetic classification (Common → Legendary) that highlights the more interesting captures (Keeloq SubGHz shows as Legendary, MIFARE NFC as Rare, BadUSB as Epic, etc.)
- **Auto-tags** — extracted from the file content (`433MHz`, `Manchester`, `Samsung`, `MIFARE`, `Scripted`, …)
- **Metadata** — frequency, preset, protocol, UID, whatever the format exposes

You can filter by type, tap a card to view / edit the raw file, and re-forge a captured payload as the starting point for a new one.

### Supported payload types and where they land on the Flipper

| Type | Extension | Flipper directory |
|------|-----------|-------------------|
| SubGHz | `.sub` | `/ext/subghz` |
| Infrared | `.ir` | `/ext/infrared` |
| NFC | `.nfc` | `/ext/nfc` |
| RFID | `.rfid` | `/ext/lfrfid` |
| BadUSB | `.txt` | `/ext/badusb` |
| iButton | `.ibtn` | `/ext/ibutton` |

Anything you can capture with the Flipper, Alchemy can author for.

### Validation

Every payload — AI-forged or hand-edited — is validated for **format correctness** before it's written to the Flipper. Errors block the save; warnings surface inline so you can decide. This catches things like out-of-range SubGHz frequencies (Flipper supports 280–930 MHz), missing headers, malformed DuckyScript, unknown modulation presets, and so on. If validation fails, you'll see exactly why.

---

## Payload Lab

**Specialized 3-step wizard for BadUSB and Evil Portal.**

Two tabs at the top: **BadUSB** and **Evil Portal**. Pick one, describe what you want, and watch the pipeline progress through three visible stages.

### The 3 steps

Each stage has its own AI prompt and shows its own progress indicator, so you can see (and abandon) the work mid-flight:

1. **Generate** — initial payload creation. For BadUSB, includes your target **platform** (Windows / macOS / Linux / cross-platform) and **category** (prank / recon / exfil / access / custom). For Evil Portal, includes **portal type** (WiFi login / social media / corporate / banking / custom) and the capture endpoint (default `/capture`).
2. **Validate** — separate AI pass that cleans up the syntax, checks for obvious bugs, and suggests follow-up actions. If validation errors out, you get the raw output with warnings attached rather than nothing.
3. **Ready** — save to Flipper, optionally search the web for related references, and (BadUSB only) execute directly — which is HIGH-risk and will need a double-tap.

### BadUSB

- **Categories:** Prank, Recon, Exfil, Access, Custom
- **Platforms:** Windows, macOS, Linux, Cross-platform
- **Built-in templates** you can start from: Hello World (Notepad), Rickroll, WiFi Password Grabber (Win), System Info Grabber (Win), Reverse Shell (Win), Disable Defender (Win), Mac Terminal, Linux Recon
- Writes to `/ext/badusb/` on the Flipper
- **BadUSB execution can affect the connected host.** Actions routed through `CommandExecutor` use its risk policy and approval settings. Direct Labs dispatch does not carry a universal double-tap guarantee; review the installed execution path before running a payload.

### Evil Portal

- **Portal types:** WiFi login, Social media, Corporate, Banking, Custom
- **Built-in templates:** Generic WiFi Login, Google-style Login, Corporate VPN, Hotel WiFi Portal
- Output is a self-contained HTML page with a form that POSTs to your configurable capture endpoint (default `/capture`)
- **Delivery:** the HTML is written to the Flipper; actually hosting the captive portal is the WiFi devboard / companion firmware's job, not Vesper's — see `marauder-overview.md` and `marauder-commands.md`

### Photo attachment

Attached photos are uploaded to remote models; processing is not on-device.
In the Evil Portal flow, `PayloadEngine.step1GeneratePortal()` passes the
base64 screenshot as an `ImageAttachment` to `sendMessagesWithoutTools()`.
That request sends the image through OpenRouter to the selected primary
model. Chat's separate image-description path also uploads images to a
remote vision model before passing the resulting description onward.
Do not attach confidential screenshots, credentials, or personal images
unless you are authorized to share them with those cloud providers.

### Notifications and errors

- **Save success** → in-screen snackbar confirmation.
- **Errors** (network, validation, write failure) → snackbar with the reason; you can retry from the current stage without starting over.
- Nothing about the payload labs uses system notifications — those are reserved for background campaigns.

### Auditing

Audit coverage depends on the execution path. Commands handled by `CommandExecutor` use its audit pipeline. Payload Lab's **Save BadUSB** and **Save Evil Portal** paths call `FlipperToolExecutor` and `FlipperFileSystem` directly; these saves do not call `AuditService`. Do not rely on the audit log as a complete record of Lab deployments, justifications, or expected effects. Record those direct saves separately when evidence is required.

---

## What the labs are not

- **Not signal capture.** Live capture belongs to the Device tab (Signal Arsenal, Spectral Oracle). The labs *author* signals; the Device tab captures them.
- **Not general-purpose chat.** For anything outside SubGHz/IR/NFC/RFID/BadUSB/iButton (or outside BadUSB/Evil Portal for Payload Lab), use the Chat tab.
- **Not a way around approvals.** No lab, no template, no "trust me" flag exempts a payload from the risk model. The AI drafts; a human still releases.

---

## See also

- [`campaigns.md`](campaigns.md) — the third Labs sub-surface (autonomous multi-phase engagements).
- [`architecture.md`](architecture.md) — deeper detail on the risk model and how approvals are enforced.
