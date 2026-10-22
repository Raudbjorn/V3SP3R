package com.vesper.flipper.domain.ralph

import android.content.Context
import androidx.work.*
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.model.ApprovalMethod
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@EntryPoint @InstallIn(SingletonComponent::class)
interface CampaignEntryPoint { fun commandExecutor(): CommandExecutor }
internal fun executor(context: Context): CommandExecutor = EntryPointAccessors.fromApplication(context, CampaignEntryPoint::class.java).commandExecutor()

class RalphOrchestrator(private val context: Context) {
    private val work get() = WorkManager.getInstance(context)
    suspend fun start(plan: CampaignPlan): String = withContext(Dispatchers.IO) {
        val id = CampaignStore(context).use { it.create(plan) }; enqueue(id); id
    }
    suspend fun enable() = withContext(Dispatchers.IO) { CampaignStore(context).use { it.enabled = true } }
    data class Inbox(val enabled: Boolean, val rows: List<CampaignStore.Row>)
    suspend fun inbox(): Inbox = withContext(Dispatchers.IO) {
        CampaignStore(context).use { store ->
            // Approval payloads live in the executor process. Never recreate or replay one after restart.
            store.rows().filter { it.state == "approval" }.forEach { row ->
                if (!available(row)) store.transition(row, "failed", "Approval unavailable or expired; create a new campaign")
            }
            Inbox(store.enabled, store.rows())
        }
    }
    private fun available(row: CampaignStore.Row): Boolean {
        val command = row.plan.phases.getOrNull(row.phase)?.commands?.getOrNull(row.step) ?: return false
        val pending = row.approval?.let { executor(context).getPendingApproval(it) }
        val now = System.currentTimeMillis()
        return now < row.approvalExpires && now < row.plan.expiresAt &&
            approvalAvailable(pending, row.approval, command, now)
    }
    fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<PhaseWorker>().setInputData(workDataOf("id" to id)).addTag(TAG).build()
        work.enqueueUniqueWork("ralph-$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    fun expiry(id: String, expires: Long) {
        val request = OneTimeWorkRequestBuilder<ApprovalExpiryWorker>().setInputData(workDataOf("id" to id))
            .setInitialDelay((expires-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS).addTag(TAG).build()
        work.enqueue(request)
    }
    suspend fun approve(id: String, displayedApprovalId: String) = withContext(Dispatchers.IO) {
        CampaignStore(context).use { store ->
            val row = store.get(id) ?: return@use
            check(row.state == "approval" && row.approval == displayedApprovalId) { "Displayed approval is no longer current" }
            if (!available(row)) {
                store.transition(row, "failed", "Approval unavailable or expired; create a new campaign")
                error("Approval unavailable or expired")
            }
            check(store.claimApproval(row, displayedApprovalId, System.currentTimeMillis())) { "Approval already consumed, expired, or disabled" }
            val deadline = minOf(row.approvalExpires, row.plan.expiresAt, row.phaseStarted + row.plan.phases[row.phase].timeoutMs)
            val result = executeCampaignApproval(deadline - System.currentTimeMillis(), onInterrupted = {
                store.get(id)?.let { current ->
                    if (current.state == "approving" && current.approval == displayedApprovalId) {
                        store.transition(current, "failed", "Approval interrupted or timed out; verify device outcome before creating a new campaign")
                    }
                }
                executor(context).reject(displayedApprovalId, "campaign-$id")
            }) {
                check(store.enabled && store.get(id)?.let { it.state == "approving" && it.approval == displayedApprovalId } == true) { "Campaign stopped before approval execution" }
                executor(context).approve(displayedApprovalId, "campaign-$id")
            }
            val claimed = store.get(id) ?: return@use
            // A kill, expiry, or rejection during execution must never resurrect the campaign.
            if (!store.enabled || claimed.state != "approving" || claimed.approval != displayedApprovalId) return@use
            if (result.success) {
                if (store.transition(claimed, "queued", step = row.step + 1)) enqueue(id)
            } else store.transition(claimed, "failed", result.error ?: "Approval failed; explicit new campaign required")
        }
    }
    suspend fun reject(id: String) = withContext(Dispatchers.IO) {
        CampaignStore(context).use { store ->
            store.cancel(id)?.let { executor(context).reject(it,"campaign-$id") }
        }
    }
    suspend fun kill() = withContext(Dispatchers.IO) {
        CampaignStore(context).use { store ->
            val pending = store.killAll()
            work.cancelAllWorkByTag(TAG)
            pending.forEach { (id, approval) -> executor(context).reject(approval,"campaign-$id") }
        }
    }
    companion object { const val TAG = "ralph-experimental" }
}

class PhaseWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val orchestration = RalphOrchestrator(applicationContext)
        CampaignStore(applicationContext).use { store ->
            var row = store.get(id) ?: return Result.failure()
            if (!store.enabled || row.state != "queued") return Result.success()
            if (System.currentTimeMillis() >= row.plan.expiresAt) { store.transition(row,"failed","Campaign expired"); return Result.failure() }
            if (row.phase >= row.plan.phases.size) { store.transition(row,"completed","All phases complete"); return Result.success() }
            val phase = row.plan.phases[row.phase]
            if (row.phaseStarted != 0L && System.currentTimeMillis() >= row.phaseStarted + phase.timeoutMs) {
                store.transition(row,"failed","Phase deadline exceeded")
                return Result.failure()
            }
            if (row.phaseStarted == 0L) {
                if (!store.transition(row,"queued",phaseStarted=System.currentTimeMillis())) return Result.success()
                row=store.get(id) ?: return Result.failure()
            }
            try {
                CampaignPolicy.validate(row.plan,System.currentTimeMillis())
                withTimeout((phase.timeoutMs - (System.currentTimeMillis()-row.phaseStarted)).coerceAtLeast(1)) {
                    while(row.step < phase.commands.size) {
                        check(store.enabled && !isStopped && System.currentTimeMillis() < row.plan.expiresAt) { "Campaign stopped or expired" }
                        check(row.step < phase.maxSteps) { "Phase step budget reached" }
                        // Record intent before execution. Process death leaves 'executing'; it never auto-replays.
                        check(store.transition(row,"executing")) { "Command already claimed" }
                        check(store.enabled && !isStopped && store.get(id)?.state == "executing") { "Campaign stopped before execution" }
                        val result = executor(applicationContext).execute(phase.commands[row.step],"campaign-$id")
                        val claimed = store.get(id) ?: error("Campaign disappeared")
                        if (claimed.state == "cancelled" || !store.enabled) {
                            result.pendingApprovalId?.let { executor(applicationContext).reject(it,"campaign-$id") }
                            return@withTimeout
                        }
                        if (result.requiresConfirmation) {
                            val expires = minOf(System.currentTimeMillis()+5*60*1000,row.plan.expiresAt,row.phaseStarted+phase.timeoutMs)
                            if (!store.transition(claimed,"approval","Review ${phase.name} step ${row.step+1}",approval=result.pendingApprovalId,approvalExpires=expires)) {
                                result.pendingApprovalId?.let { executor(applicationContext).reject(it,"campaign-$id") }
                                return@withTimeout
                            }
                            CampaignNotifications.pending(applicationContext,id)
                            orchestration.expiry(id,expires)
                            return@withTimeout
                        }
                        check(result.success) { result.error ?: "Command failed" }
                        store.transition(claimed,"queued",result.data?.message ?: "Step complete",step=row.step+1)
                        row = store.get(id) ?: error("Campaign disappeared")
                    }
                    if (row.step >= phase.commands.size && store.get(id)?.state == "queued") {
                        store.transition(row,"queued","Phase ${phase.name} complete",phase=row.phase+1,step=0,phaseStarted=0)
                        orchestration.enqueue(id)
                    }
                }
            } catch (e: CancellationException) {
                store.get(id)?.let { if(it.state != "cancelled") store.transition(it,"failed","Cancelled or timed out; outcome may need manual verification") }
                throw e
            } catch(e: Exception) {
                store.get(id)?.let { if(it.state != "cancelled") store.transition(it,"failed",e.message ?: "Campaign failed") }
                return Result.failure()
            }
        }
        return Result.success()
    }
}
class ApprovalExpiryWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        CampaignStore(applicationContext).use { store ->
            val row = store.get(inputData.getString("id") ?: return Result.failure()) ?: return Result.success()
            if (row.state in setOf("approval", "approving") && System.currentTimeMillis() >= row.approvalExpires) {
                row.approval?.let { executor(applicationContext).reject(it,"campaign-${row.id}") }
                store.transition(row,"failed", if (row.state == "approving") "Approval execution expired; verify device outcome" else "Approval expired")
                CampaignNotifications.pending(applicationContext,row.id,"Campaign approval expired")
            }
        }
        return Result.success()
    }
}
