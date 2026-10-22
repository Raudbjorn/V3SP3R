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
import java.util.concurrent.TimeUnit

@EntryPoint @InstallIn(SingletonComponent::class)
interface CampaignEntryPoint { fun commandExecutor(): CommandExecutor }
internal fun executor(context: Context): CommandExecutor = EntryPointAccessors.fromApplication(context, CampaignEntryPoint::class.java).commandExecutor()

class RalphOrchestrator(private val context: Context) {
    private val work get() = WorkManager.getInstance(context)
    fun start(plan: CampaignPlan): String {
        val id = CampaignStore(context).use { it.create(plan) }; enqueue(id); return id
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
    suspend fun approve(id: String) {
        CampaignStore(context).use { store ->
            val row = store.get(id) ?: return
            check(store.enabled && row.state == "approval" && System.currentTimeMillis() < row.approvalExpires && System.currentTimeMillis() < row.plan.expiresAt) { "Approval expired or campaigns disabled" }
            check(store.transition(row,"approving")) { "Approval already consumed" }
            val result = executor(context).approve(requireNotNull(row.approval), "campaign-$id")
            val claimed = store.get(id) ?: return
            // A kill/reject during the in-flight approval must never resurrect the campaign.
            if (!store.enabled || claimed.state != "approving") return
            if (result.success) {
                store.transition(claimed,"queued",step = row.step + 1)
                enqueue(id)
            } else store.transition(claimed,"failed", result.error ?: "Approval failed; explicit new campaign required")
        }
    }
    suspend fun reject(id: String) {
        CampaignStore(context).use { store ->
            val row = store.get(id) ?: return
            row.approval?.let { executor(context).reject(it,"campaign-$id") }
            store.transition(row,"cancelled","Rejected by operator")
        }
    }
    suspend fun kill() {
        CampaignStore(context).use { store ->
            val pending = store.rows().filter { it.state == "approval" }
            store.killAll()
            work.cancelAllWorkByTag(TAG)
            pending.forEach { row -> row.approval?.let { executor(context).reject(it,"campaign-${row.id}") } }
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
                        if (claimed.state == "cancelled") return@withTimeout
                        if (result.requiresConfirmation) {
                            val expires = minOf(System.currentTimeMillis()+5*60*1000,row.plan.expiresAt,row.phaseStarted+phase.timeoutMs)
                            store.transition(claimed,"approval","Review ${phase.name} step ${row.step+1}",approval=result.pendingApprovalId,approvalExpires=expires)
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
            if (row.state == "approval" && System.currentTimeMillis() >= row.approvalExpires) {
                row.approval?.let { executor(applicationContext).reject(it,"campaign-${row.id}") }
                store.transition(row,"failed","Approval expired")
                CampaignNotifications.pending(applicationContext,row.id,"Campaign approval expired")
            }
        }
        return Result.success()
    }
}
