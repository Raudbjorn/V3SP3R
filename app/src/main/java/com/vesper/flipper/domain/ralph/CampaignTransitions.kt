package com.vesper.flipper.domain.ralph

/** Persisted lifecycle policy. Terminal outcomes cannot be replayed or resurrected. */
object CampaignTransitions {
    private val next = mapOf(
        "queued" to setOf("queued", "executing", "completed", "failed", "cancelled"),
        "executing" to setOf("queued", "approval", "failed", "cancelled"),
        "approval" to setOf("approving", "failed", "cancelled"),
        "approving" to setOf("queued", "failed", "cancelled")
    )
    fun allows(from: String, to: String): Boolean = to in next[from].orEmpty()
    fun canApprove(enabled: Boolean, state: String, now: Long, approvalExpiry: Long, campaignExpiry: Long): Boolean =
        enabled && state == "approval" && now < approvalExpiry && now < campaignExpiry
}
