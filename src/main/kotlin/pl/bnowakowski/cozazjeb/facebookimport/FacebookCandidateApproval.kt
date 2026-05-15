// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

data class FacebookCandidateApproval(
    val url: String,
    val language: String,
    val candidateId: String = "",
    val sourcePostUrl: String? = null,
    val decision: FacebookCandidateApprovalDecision = FacebookCandidateApprovalDecision.ACCEPT,
)

enum class FacebookCandidateApprovalDecision {
    ACCEPT,
    REJECT,
}

fun interface FacebookCandidateApprovalHandler {
    fun approve(candidates: List<FacebookCandidateApproval>): List<FacebookCandidateApproval>

    companion object {
        fun acceptAll(): FacebookCandidateApprovalHandler =
            FacebookCandidateApprovalHandler { candidates ->
                candidates.map { it.copy(decision = FacebookCandidateApprovalDecision.ACCEPT) }
            }
    }
}
