package com.vesper.flipper.domain.service

import com.vesper.flipper.data.database.VulnDao
import com.vesper.flipper.data.database.VulnFindingEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ARTEMIS-style vulnerability triage: submit → validate (reproduce) → classify (auto-severity) →
 * list (with filters). Ported from FlipperAgent's `vuln_triage` module; JSON-file persistence
 * replaced with Room ([VulnDao] / [VulnFindingEntity]).
 *
 * All ops are pure data — this service does not touch the Flipper. Actions using it therefore
 * inherit LOW/MEDIUM risk based on FlipperAgent's TOOL_RISK_MAP (submit/list/classify LOW,
 * validate MEDIUM).
 */
@Singleton
class VulnTriageService @Inject constructor(
    private val vulnDao: VulnDao,
) {

    enum class Severity(val wire: String) {
        CRITICAL("critical"), HIGH("high"), MEDIUM("medium"), LOW("low");

        companion object {
            fun from(raw: String?): Severity? =
                raw?.trim()?.lowercase()?.let { s -> values().firstOrNull { it.wire == s } }
        }
    }

    enum class Status(val wire: String) {
        SUBMITTED("submitted"),
        CONFIRMED("confirmed"),
        INCONCLUSIVE("inconclusive"),
        REJECTED("rejected"),
        FALSE_POSITIVE("false_positive");

        companion object {
            fun from(raw: String?): Status? =
                raw?.trim()?.lowercase()?.let { s -> values().firstOrNull { it.wire == s } }
        }
    }

    @Serializable
    data class ValidationNote(
        val timestamp: Long,
        val reproduced: Boolean,
        val notes: String,
    )

    data class SubmitRequest(
        val target: String,
        val vulnType: String,
        val description: String,
        val evidence: String,
        val severity: Severity,
        val complexity: Int,
    )

    data class ValidateRequest(
        val vulnId: String,
        val reproduced: Boolean,
        val notes: String?,
    )

    data class ListFilters(
        val target: String?,
        val severity: Severity?,
        val status: Status?,
    )

    private val mutationMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ─── Public API ───────────────────────────────────────────────────────────

    suspend fun submit(req: SubmitRequest): VulnFindingEntity {
        require(req.target.length <= 512 && req.vulnType.length <= 128 && req.description.length <= 8192 && req.evidence.length <= 65536) { "Finding exceeds field size limits" }
        require(req.complexity in 1..10) { "complexity must be between 1 and 10" }
        require(req.target.isNotBlank()) { "target required" }
        require(req.vulnType.isNotBlank()) { "vuln_type required" }
        require(req.description.isNotBlank()) { "description required" }
        require(req.evidence.isNotBlank()) { "evidence required" }
        val entity = VulnFindingEntity(
            id = UUID.randomUUID().toString(),
            submittedAt = System.currentTimeMillis(),
            target = req.target.trim(),
            vulnType = req.vulnType.trim().lowercase(),
            description = req.description.trim(),
            evidence = req.evidence,
            severity = req.severity.wire,
            complexity = req.complexity,
            status = Status.SUBMITTED.wire,
        )
        vulnDao.upsert(entity)
        return entity
    }

    /**
     * Record reproduction evidence without treating one failed attempt as disproof:
     *   reproduced=true  → confirmed
     *   reproduced=false → inconclusive (prior evidence remains in history)
     */
    suspend fun validate(req: ValidateRequest): VulnFindingEntity? = mutationMutex.withLock {
        require((req.notes?.length ?: 0) <= 8192) { "Validation notes too long" }
        val existing = vulnDao.getById(req.vulnId) ?: return@withLock null
        val now = System.currentTimeMillis()
        val newStatus = when {
            req.reproduced -> Status.CONFIRMED.wire
            else -> Status.INCONCLUSIVE.wire
        }
        val notes = req.notes ?: ""
        val notesJson = if (notes == null) {
            existing.validationNotesJson
        } else {
            val prior: List<ValidationNote> = try {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ValidationNote.serializer()),
                    existing.validationNotesJson,
                )
            } catch (e: Exception) {
                throw IllegalStateException("Existing validation history is malformed; refusing to overwrite it", e)
            }
            require(prior.size < 100) { "Validation history is full" }
            val next = prior + ValidationNote(now, req.reproduced, notes)
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ValidationNote.serializer()),
                next,
            )
        }
        val updated = existing.copy(
            status = newStatus,
            reproductionAttempts = existing.reproductionAttempts + 1,
            validatedAt = now,
            validationNotesJson = notesJson,
        )
        vulnDao.upsert(updated)
        updated
    }

    suspend fun list(filters: ListFilters): List<VulnFindingEntity> {
        val all = vulnDao.queryFiltered(filters.target, filters.severity?.wire, filters.status?.wire)
        val filtered = all.filter { v ->
            val targetOk = filters.target?.let { needle ->
                v.target.contains(needle, ignoreCase = true)
            } ?: true
            val sevOk = filters.severity?.let { v.severity == it.wire } ?: true
            val statusOk = filters.status?.let { v.status == it.wire } ?: true
            targetOk && sevOk && statusOk
        }
        // Sort critical → low, then most recently submitted first within a severity.
        return filtered.sortedWith(
            compareBy<VulnFindingEntity> { severityOrder(it.severity) }
                .thenByDescending { it.submittedAt }
        )
    }

    data class Classification(
        val vulnType: String,
        val severity: Severity,
        val matchedRule: String?,
        val updatedFinding: VulnFindingEntity?,
    )

    /**
     * Look up [vulnType] in the ARTEMIS rules: exact match first, then substring in either
     * direction. Falls through to MEDIUM. Optionally rewrites an existing finding's severity.
     */
    suspend fun classify(vulnType: String, vulnId: String?): Classification = mutationMutex.withLock {
        require(vulnType.isNotBlank() && vulnType.length <= 128) { "Invalid vulnerability type" }
        val needle = vulnType.trim().lowercase()
        val (severity, matchedKey) = severityFor(needle)
        val updated = vulnId?.let {
            val existing = vulnDao.getById(it) ?: throw IllegalArgumentException("Unknown finding ID")
            existing.let { existing ->
                val next = existing.copy(severity = severity.wire)
                vulnDao.upsert(next)
                next
            }
        }
        Classification(
            vulnType = needle,
            severity = severity,
            matchedRule = matchedKey,
            updatedFinding = updated,
        )
    }

    // ─── Rendering helpers used by the executor ───────────────────────────────

    fun renderList(findings: List<VulnFindingEntity>): String {
        if (findings.isEmpty()) return "No vulnerabilities found matching filters."
        val bySeverity = findings.groupingBy { it.severity }.eachCount()
        val byStatus = findings.groupingBy { it.status }.eachCount()
        val header = buildString {
            appendLine("Vulnerabilities: ${findings.size} total")
            appendLine("  By severity: ${bySeverity.entries.sortedBy { severityOrder(it.key) }.joinToString(", ") { "${it.key.uppercase()}=${it.value}" }}")
            appendLine("  By status:   ${byStatus.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }}")
        }
        val rows = findings.joinToString("\n") { v ->
            val icon = when (v.status) {
                Status.SUBMITTED.wire -> "[?]"
                Status.CONFIRMED.wire -> "[!]"
                Status.REJECTED.wire -> "[x]"
                Status.FALSE_POSITIVE.wire -> "[-]"
                else -> "[?]"
            }
            val descPreview = v.description.take(120)
            "  $icon ${v.id} | ${v.severity.uppercase()} | ${v.target} | ${v.vulnType} | status=${v.status} | complexity=${v.complexity}/10\n      $descPreview"
        }
        return header + "\n" + rows
    }

    fun renderFinding(v: VulnFindingEntity, prefix: String = "Vulnerability"): String = buildString {
        appendLine("$prefix ${v.id}:")
        appendLine("  Target:     ${v.target}")
        appendLine("  Type:       ${v.vulnType}")
        appendLine("  Severity:   ${v.severity.uppercase()}")
        appendLine("  Complexity: ${v.complexity}/10")
        appendLine("  Status:     ${v.status}")
        appendLine("  Attempts:   ${v.reproductionAttempts}")
        appendLine("  Description: ${v.description}")
    }.trimEnd()

    fun renderClassification(c: Classification): String = buildString {
        appendLine("Auto-classification: ${c.vulnType} -> ${c.severity.wire.uppercase()}")
        c.matchedRule?.let { appendLine("  matched rule: $it") }
        c.updatedFinding?.let { appendLine("  updated ${it.id}: severity now ${it.severity.uppercase()}") }
        appendLine()
        appendLine("Classification rules reference:")
        appendLine("  CRITICAL: default_creds, no_auth, rce, command_injection, hardcoded_password")
        appendLine("  HIGH: writable_ble, open_admin_port, ssh/telnet_open, sqli, directory_traversal")
        appendLine("  MEDIUM: xss, csrf, open_port, outdated_firmware/software")
        appendLine("  LOW: info_disclosure, version_leak, banner_grab, dns_leak")
    }.trimEnd()

    // ─── ARTEMIS rules (ported unchanged from FlipperAgent) ───────────────────

    private fun severityFor(vulnType: String): Pair<Severity, String?> {
        SEVERITY_RULES[vulnType]?.let { return it to vulnType }
        // Exact rule keys only; a fragment such as "r" is not evidence of RCE.
        return Severity.MEDIUM to null
    }

    private fun severityOrder(sev: String): Int = when (sev.lowercase()) {
        "critical" -> 0
        "high" -> 1
        "medium" -> 2
        "low" -> 3
        else -> 4
    }

    companion object {
        private val SEVERITY_RULES: Map<String, Severity> = linkedMapOf(
            "default_creds" to Severity.CRITICAL,
            "default_credentials" to Severity.CRITICAL,
            "default_password" to Severity.CRITICAL,
            "hardcoded_password" to Severity.CRITICAL,
            "no_auth" to Severity.CRITICAL,
            "unauthenticated" to Severity.CRITICAL,
            "rce" to Severity.CRITICAL,
            "remote_code_execution" to Severity.CRITICAL,
            "command_injection" to Severity.CRITICAL,
            "writable_ble" to Severity.HIGH,
            "writable_characteristic" to Severity.HIGH,
            "open_admin_port" to Severity.HIGH,
            "admin_panel_exposed" to Severity.HIGH,
            "ssh_open" to Severity.HIGH,
            "telnet_open" to Severity.HIGH,
            "unencrypted_protocol" to Severity.HIGH,
            "weak_encryption" to Severity.HIGH,
            "directory_traversal" to Severity.HIGH,
            "sqli" to Severity.HIGH,
            "sql_injection" to Severity.HIGH,
            "xss" to Severity.MEDIUM,
            "csrf" to Severity.MEDIUM,
            "open_port" to Severity.MEDIUM,
            "outdated_firmware" to Severity.MEDIUM,
            "outdated_software" to Severity.MEDIUM,
            "info_disclosure" to Severity.LOW,
            "information_disclosure" to Severity.LOW,
            "version_leak" to Severity.LOW,
            "banner_grab" to Severity.LOW,
            "dns_leak" to Severity.LOW,
        )
    }
}
