package com.vesper.flipper.domain.ralph

import com.vesper.flipper.domain.model.*
import kotlinx.serialization.Serializable

@Serializable data class CampaignPlan(
    val title: String,
    val scope: String,
    val expiresAt: Long,
    val phases: List<CampaignPhase>
)
@Serializable data class CampaignPhase(
    val name: String,
    val commands: List<ExecuteCommand>,
    val timeoutMs: Long = 60_000,
    val maxSteps: Int = 10
)

/** Deliberately bounded initial campaign vocabulary; no nested runbooks or raw CLI. */
object CampaignPolicy {
    private val allowed = setOf(CommandAction.LIST_DIRECTORY, CommandAction.READ_FILE,
        CommandAction.GET_DEVICE_INFO, CommandAction.GET_STORAGE_INFO,
        CommandAction.WRITE_FILE, CommandAction.CREATE_DIRECTORY)
    fun validate(plan: CampaignPlan, now: Long) {
        require(plan.title.isNotBlank() && plan.title.length <= 100)
        require(plan.scope.startsWith("/ext/") && canonical(plan.scope)) { "Scope must be an exact /ext directory" }
        require(plan.expiresAt > now && plan.expiresAt - now <= 24 * 60 * 60 * 1000) { "Campaign must expire within 24 hours" }
        require(plan.phases.size in 1..5)
        require(plan.phases.map { it.name }.distinct().size == plan.phases.size)
        plan.phases.forEach { phase ->
            require(phase.name.isNotBlank() && phase.name.length <= 80)
            require(phase.maxSteps in 1..20 && phase.commands.size in 1..phase.maxSteps)
            require(phase.timeoutMs in 1_000..300_000)
            phase.commands.forEach { command ->
                require(command.action in allowed) { "Action is not campaign-safe: ${command.action}" }
                if (command.action !in setOf(CommandAction.GET_DEVICE_INFO, CommandAction.GET_STORAGE_INFO)) {
                    val path = requireNotNull(command.args.path)
                    require(inScope(path, plan.scope)) { "Command is outside campaign scope" }
                }
                require((command.args.content?.length ?: 0) <= 16_384)
                require(command.justification.isNotBlank() && command.expectedEffect.isNotBlank())
            }
        }
    }
    fun inScope(path: String, scope: String): Boolean = canonical(path) && canonical(scope) &&
        (path == scope || path.startsWith(scope.trimEnd('/') + "/"))
    private fun canonical(path: String) = path.startsWith("/") && !path.contains("//") &&
        !path.contains('\\') && path.none { it.code < 32 } && path.split('/').none { it == "." || it == ".." }
}
