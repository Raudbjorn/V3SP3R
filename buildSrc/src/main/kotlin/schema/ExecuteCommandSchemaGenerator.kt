package schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Emits `docs/execute_command_schema.json` from:
 *   - the `@SerialName("...")` values on `CommandAction` in `Command.kt` (source of truth for action names)
 *   - a per-action arg-shape spec declared below (source of truth for which args each action uses)
 *
 * The generator parses `Command.kt` as text rather than reflecting into the compiled app, because
 * the app's runtime classpath is Android (Room/Hilt/Compose) and cannot be loaded from buildSrc.
 * The parse is intentionally strict: each `CommandAction` entry must have a preceding
 * `@SerialName("literal")`, and the extracted literal is the JSON action string.
 *
 * The JSON is a contract for documentation and MCP schema consumers.
 * The verify task compares parsed JSON (semantic equivalence),
 * not raw text.
 */
object ExecuteCommandSchemaGenerator {

    // ─── Per-action arg-shape spec ────────────────────────────────────────────
    // Order matches the CommandAction enum in Command.kt; enforceCoverage() checks that.

    private val ACTIONS: List<String> = listOf(
        "list_directory",
        "read_file",
        "write_file",
        "create_directory",
        "delete",
        "move",
        "rename",
        "copy",
        "get_device_info",
        "get_storage_info",
        "search_faphub",
        "install_faphub_app",
        "push_artifact",
        "execute_cli",
        "forge_payload",
        "search_resources",
        "list_vault",
        "run_runbook",
        "launch_app",
        "subghz_transmit",
        "ir_transmit",
        "nfc_emulate",
        "rfid_emulate",
        "ibutton_emulate",
        "badusb_execute",
        "ble_spam",
        "led_control",
        "vibro_control",
        "browse_repo",
        "download_resource",
        "github_search",
        "request_photo",
        "input_send",
        "loader_list",
        "loader_close",
        "loader_signal",
        "load_skill",
        "ble_scan_targets",
        "ble_enumerate",
        "ble_read_char",
        "ble_write_char",
        "ble_subscribe",
    )

    private data class ArgSpec(
        val name: String,
        val type: String,
        val description: String,
        val enum: List<String>? = null,
        val default: JsonPrimitive? = null,
        val nullable: Boolean = true,
    )

    private val ARGS: List<ArgSpec> = listOf(
        ArgSpec("key", "string", "Required for input_send: GUI key; uses the RPC input path with current risk approval.",
            enum = listOf("up", "down", "left", "right", "ok", "back")),
        ArgSpec("press_type", "string", "For input_send: event type; omitted/null defaults to short in the executor.",
            enum = listOf("press", "release", "short", "long", "repeat")),
        ArgSpec("signal_id", "integer", "Required for loader_signal: nonnegative application signal ID. Loader control is one-shot USB CLI only; unavailable while RPC is active or streaming stop is uncertain."),
        ArgSpec("command", "string",
            "Free-form command string. For execute_cli: the CLI command. For search_faphub/github_search/search_resources: the query. For install_faphub_app: the app id or name. For forge_payload: fallback natural-language prompt. For launch_app: fallback app name. For run_runbook: fallback runbook id. For browse_repo: fallback repo id. For load_skill: bundled methodology ID (read-only; grants no execution approval)."),
        ArgSpec("path", "string",
            "File or directory path on Flipper (e.g., /ext/subghz/garage.sub). Also the source path for move/copy/rename, target path for push_artifact/download_resource, and signal file for subghz_transmit/ir_transmit/nfc_emulate/rfid_emulate/ibutton_emulate/badusb_execute."),
        ArgSpec("destination_path", "string", "Destination path for move/copy operations"),
        ArgSpec("content", "string",
            "Content to write to file (write_file), or BLE characteristic bytes/text (ble_write_char; encoding controlled by hex). Optional loader_signal argument, rejecting newline, carriage return, NUL, semicolon, pipe and ampersand. Also used as fallback for execute_cli. Catalog installation does not accept a direct URL override."),
        ArgSpec("new_name", "string", "New name for rename operation"),
        ArgSpec("recursive", "boolean", "Whether to delete recursively (for delete action)",
            default = JsonPrimitive(false), nullable = false),
        ArgSpec("artifact_type", "string", "Type of artifact being pushed (for push_artifact action)"),
        ArgSpec("artifact_data", "string", "Base64-encoded artifact data (for push_artifact action)"),
        ArgSpec("prompt", "string", "Natural-language specification for forge_payload"),
        ArgSpec("resource_type", "string", "Resource type filter for search_resources"),
        ArgSpec("runbook_id", "string", "Runbook identifier (for run_runbook action)"),
        ArgSpec("payload_type", "string", "Type of payload to generate (for forge_payload action)",
            enum = listOf("subghz", "ir", "badusb", "nfc")),
        ArgSpec("filter", "string", "Filter string for list_vault"),
        ArgSpec("app_name", "string", "Application name (for launch_app action)"),
        ArgSpec("app_args", "string", "Arguments passed to the launched app; also used as the ble_spam mode string"),
        ArgSpec("frequency", "integer", "Reserved metadata; file-based SubGHz transmission uses the frequency stored in the file, not this field"),
        ArgSpec("protocol", "string", "Reserved metadata; file-based transmit/emulation uses the protocol stored in the file, not this field"),
        ArgSpec("address", "string", "Phone BLE target MAC address, required for enumerate/read/write/subscribe. File-based radio transmit/emulation uses the address stored in the file, not this field"),
        ArgSpec("signal_name", "string", "Named signal for IR transmit operations"),
        ArgSpec("enabled", "boolean", "On/off state (for vibro_control action; defaults to true)"),
        ArgSpec("red", "integer", "Red LED intensity 0-255 (for led_control action)"),
        ArgSpec("green", "integer", "Green LED intensity 0-255 (for led_control action)"),
        ArgSpec("blue", "integer", "Blue LED intensity 0-255 (for led_control action)"),
        ArgSpec("repo_id", "string", "Resource repository identifier (for browse_repo action)"),
        ArgSpec("sub_path", "string", "Sub-path within a browsed repo (for browse_repo action)"),
        ArgSpec("download_url", "string",
            "Direct resource URL for download_resource. Catalog install uses an exact application ID via command and a verified compatible build; URL overrides are not accepted."),
        ArgSpec("search_scope", "string",
            "GitHub search scope, e.g. \"code\" or \"repositories\" (for github_search action; defaults to \"code\")"),
        ArgSpec("photo_prompt", "string", "Prompt describing what to capture (for request_photo action)"),
        ArgSpec("duration", "number", "Phone BLE actions: seconds, default 5, range 0.5..60. Scan/listen duration for scan/subscribe; connection and operation timeout for enumerate/read/write. Subscription connection timeout is 10 seconds; restoration adds at most 3 seconds."),
        ArgSpec("uuid", "string", "Full characteristic UUID required for BLE read/write/subscribe; must identify exactly one characteristic instance."),
        ArgSpec("hex", "boolean", "ble_write_char encoding: true (default) means hexadecimal byte pairs; false means UTF-8 text. Maximum 512 bytes."),
        ArgSpec("with_response", "boolean", "ble_write_char only: true (default) waits for a GATT response. False sends without a peripheral response and rejects data exceeding negotiated MTU minus 3; no fragmentation."),

    )

    private data class Example(
        val action: String,
        val args: Map<String, JsonPrimitive>,
        val justification: String,
        val expectedEffect: String,
    )

    private fun s(v: String) = JsonPrimitive(v)
    private fun i(v: Int) = JsonPrimitive(v)
    private fun b(v: Boolean) = JsonPrimitive(v)

    private val EXAMPLES: List<Example> = listOf(
        Example("ble_scan_targets", mapOf("duration" to JsonPrimitive(5.0)),
            "User requested a nearby BLE scan", "Return advertising device metadata using the phone radio"),
        Example("ble_enumerate", mapOf("address" to s("AA:BB:CC:DD:EE:FF")),
            "User requested the selected peripheral services", "Connect to the selected phone BLE peripheral and list its GATT services"),
        Example("ble_read_char", mapOf("address" to s("AA:BB:CC:DD:EE:FF"), "uuid" to s("00002a19-0000-1000-8000-00805f9b34fb")),
            "User requested a characteristic read", "Read the uniquely identified characteristic"),
        Example("ble_write_char", mapOf("address" to s("AA:BB:CC:DD:EE:FF"), "uuid" to s("00002a19-0000-1000-8000-00805f9b34fb"), "content" to s("01")),
            "User requested this exact byte write", "Request approval showing the peripheral, characteristic and bytes before writing"),
        Example("ble_subscribe", mapOf("address" to s("AA:BB:CC:DD:EE:FF"), "uuid" to s("00002a19-0000-1000-8000-00805f9b34fb")),
            "User requested a bounded characteristic subscription", "Request explicit approval, listen for the chosen duration and restore the original remote subscription descriptor"),
        Example("input_send", mapOf("key" to s("back"), "press_type" to s("short")),
            "User requested one Back button action", "Send one RPC GUI input event after required approval"),
        Example("loader_list", emptyMap(),
            "User requested the application list", "List applications through the supported loader command route"),
        Example("loader_close", emptyMap(),
            "User requested closing the active application", "Send one USB CLI loader close command only when the current session is confirmed CLI-ready"),
        Example("loader_signal", mapOf("signal_id" to i(1)),
            "User requested application signal 1", "Send one USB CLI loader signal command only when the current session is confirmed CLI-ready"),
        Example("list_directory", mapOf("path" to s("/ext/subghz")),
            "User wants to see their SubGHz captures",
            "Return a list of .sub files in the SubGHz directory"),
        Example("read_file", mapOf("path" to s("/ext/subghz/garage.sub")),
            "User wants to inspect the garage remote configuration",
            "Return the contents of the garage.sub file"),
        Example("write_file", mapOf(
            "path" to s("/ext/subghz/garage.sub"),
            "content" to s("Filetype: Flipper SubGhz Key File\nVersion: 1\nFrequency: 315000000\n..."),
        ),
            "User wants to change the frequency from 390MHz to 315MHz",
            "Update the garage.sub file with the new frequency"),
        Example("subghz_transmit", mapOf("path" to s("/ext/subghz/garage.sub")),
            "User wants to transmit the garage signal",
            "Transmit the SubGHz signal stored in garage.sub"),
        Example("execute_cli", mapOf("command" to s("info")),
            "User wants detailed system information from the Flipper",
            "Return system info output from the Flipper CLI"),
        Example("launch_app", mapOf("app_name" to s("Sub-GHz"), "app_args" to s("")),
            "User wants to open the SubGHz app on the Flipper",
            "Launch the Sub-GHz application on the Flipper Zero"),
        Example("led_control", mapOf("red" to i(0), "green" to i(255), "blue" to i(0)),
            "User wants visual feedback from the Flipper",
            "Turn on the green LED on the Flipper Zero"),
        Example("vibro_control", mapOf("enabled" to b(true)),
            "User wants haptic feedback",
            "Turn on the vibration motor"),
        Example("search_faphub", mapOf("command" to s("wifi scanner")),
            "User is looking for a WiFi scanning app",
            "Return matching applications from the FapHub catalog"),
        Example("install_faphub_app", mapOf(
            "command" to s("wifi_marauder"),
        ),
            "User wants to install the selected FapHub app",
            "Install the WiFi Marauder .fap onto the Flipper"),
        Example("forge_payload", mapOf(
            "payload_type" to s("badusb"),
            "prompt" to s("Open a browser to example.com"),
        ),
            "User wants a generated BadUSB payload",
            "Return a forged BadUSB script matching the spec"),
        Example("search_resources", mapOf(
            "command" to s("garage"),
            "resource_type" to s("subghz"),
        ),
            "User is browsing community SubGHz resources",
            "Return matching resource repositories"),
        Example("browse_repo", mapOf(
            "repo_id" to s("unleashed-firmware"),
            "sub_path" to s("subghz"),
        ),
            "User wants to inspect a specific resource repo",
            "List contents at the given sub-path within the repo"),
        Example("download_resource", mapOf(
            "download_url" to s("https://example.com/garage.sub"),
            "path" to s("/ext/subghz/garage.sub"),
        ),
            "User wants to download a signal file",
            "Download the resource to the given Flipper path"),
        Example("github_search", mapOf(
            "command" to s("flipper subghz replay"),
            "search_scope" to s("code"),
        ),
            "User wants GitHub code search results",
            "Return matching GitHub code results"),
        Example("list_vault", mapOf(
            "path" to s("/ext"),
            "filter" to s("*.sub"),
        ),
            "User wants to browse vault contents",
            "Return filtered vault entries"),
        Example("run_runbook", mapOf("runbook_id" to s("recon_basic")),
            "User wants to run a pre-defined recon routine",
            "Execute the referenced runbook"),
        Example("ble_spam", mapOf("app_args" to s("apple")),
            "User wants to run a BLE spam mode",
            "Launch BLE spam with the given mode"),
        Example("load_skill", mapOf("command" to s("signal-analysis")),
            "User requested signal analysis methodology",
            "Return the bundled read-only playbook; execution still requires normal risk approval"),
        Example("request_photo", mapOf("photo_prompt" to s("capture the target device screen")),
            "User wants a picture taken via smartglasses",
            "Trigger a photo capture at the agent layer"),
        Example("get_device_info", emptyMap(),
            "User wants to check device status",
            "Return battery level, firmware version, and hardware info"),
    )

    // ─── Public entry points ──────────────────────────────────────────────────

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Verifies [commandKt]'s CommandAction enum matches [ACTIONS] exactly. */
    fun enforceCoverage(commandKt: File) {
        val enumSerialNames = parseCommandActionSerialNames(commandKt)
        val extra = enumSerialNames - ACTIONS.toSet()
        val missing = ACTIONS - enumSerialNames.toSet()
        val problems = buildList {
            if (extra.isNotEmpty()) add("enum has actions the generator doesn't describe: $extra")
            if (missing.isNotEmpty()) add("generator describes actions not present in enum: $missing")
            if (enumSerialNames.toSet() == ACTIONS.toSet() && enumSerialNames != ACTIONS) {
                add("enum action order differs from generator; enum: $enumSerialNames")
            }
        }
        if (problems.isNotEmpty()) {
            error(
                "CommandAction / schema generator drift:\n  - " +
                    problems.joinToString("\n  - ") +
                    "\nUpdate ACTIONS + ARGS + EXAMPLES in ExecuteCommandSchemaGenerator to match Command.kt."
            )
        }
        enforceArgumentCoverage(commandKt)
    }

    private data class RuntimeArg(val name: String, val type: String, val nullable: Boolean, val default: String)

    /** Fail closed on unsupported syntax as well as serialized name/type/nullability/default drift. */
    internal fun enforceArgumentCoverage(commandKt: File) {
        val text = commandKt.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().map { it.substringBefore("//") }.joinToString("\n")
        val body = Regex("""data\s+class\s+CommandArgs\s*\((.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: error("CommandArgs declaration not found")
        val field = Regex("""(?:@SerialName\("([^"]+)"\)\s*)?val\s+(\w+)\s*:\s*(String|Boolean|Int|Long|Double|Float)(\?)?\s*=\s*(null|true|false|-?\d+(?:\.\d+)?|"[^"]*")""")
        val actual = body.split(',').filter { it.isNotBlank() }.map { declaration ->
            val match = field.matchEntire(declaration.trim()) ?: error("Unsupported CommandArgs syntax: $declaration")
            val (serialized, name, type, nullable, default) = match.destructured
            RuntimeArg(serialized.ifEmpty { name }, when(type) {
                "String" -> "string"; "Boolean" -> "boolean"; "Int", "Long" -> "integer"; else -> "number"
            }, nullable.isNotEmpty(), default)
        }
        val expected = ARGS.map { RuntimeArg(it.name, it.type, it.nullable, it.default?.toString() ?: "null") }
        check(actual == expected) { "CommandArgs / schema generator drift: actual=$actual; documented=$expected. Update ARGS and regenerate." }
    }

    /** Emits the JSON schema pretty-printed with a trailing newline. */
    fun render(): String = prettyJson.encodeToString(JsonElement.serializer(), build()) + "\n"

    /** Parse arbitrary JSON — used by verify to compare committed vs. generated semantically. */
    fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    // ─── Building the schema tree ─────────────────────────────────────────────

    private fun build(): JsonObject = buildJsonObject {
        put("\$schema", "http://json-schema.org/draft-07/schema#")
        put("title", "execute_command")
        put(
            "description",
            "Execute a command on the Flipper Zero device. All file operations, device queries, hardware control, and artifact pushes go through this interface. Mirrors com.vesper.flipper.domain.model.ExecuteCommand.",
        )
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { ACTIONS.forEach { add(it) } }
                put("description", "The action to perform on the Flipper Zero")
            }
            putJsonObject("args") {
                put("type", "object")
                put(
                    "description",
                    "Arguments for the action (varies by action type). Unused fields may be omitted.",
                )
                putJsonObject("properties") {
                    for (arg in ARGS) {
                        putJsonObject(arg.name) {
                            put("type", arg.type)
                            if (arg.enum != null) {
                                putJsonArray("enum") { arg.enum.forEach { add(it) } }
                            }
                            put("description", arg.description)
                            if (arg.default != null) {
                                put("default", arg.default)
                            }
                        }
                    }
                }
            }
            putJsonObject("justification") {
                put("type", "string")
                put("description", "Explanation of why this action is being taken")
            }
            putJsonObject("expected_effect") {
                put("type", "string")
                put("description", "What you expect this action to accomplish")
            }
        }
        putJsonArray("required") {
            add("action"); add("args"); add("justification"); add("expected_effect")
        }
        putJsonArray("examples") {
            for (ex in EXAMPLES) {
                addJsonObject {
                    put("action", ex.action)
                    putJsonObject("args") {
                        for ((k, v) in ex.args) put(k, v)
                    }
                    put("justification", ex.justification)
                    put("expected_effect", ex.expectedEffect)
                }
            }
        }
    }

    // ─── Parsing Command.kt ───────────────────────────────────────────────────

    private val serialNameRegex = Regex("""@SerialName\("([^"]+)"\)""")
    private val enumEntryRegex = Regex("""^([A-Z][A-Z0-9_]*)\s*[,;]?$""")

    private fun parseCommandActionSerialNames(commandKt: File): List<String> {
        val text = commandKt.readText()
        val enumStart = Regex("""enum\s+class\s+CommandAction\s*\{""").find(text)
            ?: error("CommandAction enum not found in ${commandKt.name}")
        val bodyStart = enumStart.range.last + 1
        var depth = 1
        var i = bodyStart
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) break
            i++
        }
        check(depth == 0) { "unbalanced braces in CommandAction enum body" }
        val body = text.substring(bodyStart, i)

        val result = mutableListOf<String>()
        var pendingSerialName: String? = null
        val withoutComments = body.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().map { it.substringBefore("//") }.joinToString("\n")
        for (raw in withoutComments.replace(",", ",\n").lines()) {
            var line = raw.substringBefore("//").trim()
            if (line.isEmpty()) continue
            serialNameRegex.find(line)?.let {
                check(pendingSerialName == null) { "Multiple SerialName annotations before enum entry" }
                pendingSerialName = it.groupValues[1]
                line = line.removeRange(it.range).trim()
            }
            if (line.isEmpty()) continue
            val entry = enumEntryRegex.matchEntire(line)
                ?: error("Unsupported CommandAction syntax: $line")
            val name = pendingSerialName
                ?: error("CommandAction entry ${entry.groupValues[1]} has no preceding @SerialName")
            result += name
            pendingSerialName = null
        }
        check(pendingSerialName == null) { "SerialName without a CommandAction entry" }
        return result
    }
}
