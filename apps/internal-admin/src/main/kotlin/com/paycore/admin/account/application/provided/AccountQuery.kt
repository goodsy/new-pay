package com.paycore.admin.account.application.provided

/**
 * 계정 목록(화면 — S7). 조회자 권한은 계정 현재 행(ACTIVE + VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN 중 하나 — §4.5.1 "마스킹된 조회"). 권한이 없으면 null(웹 403).
 * 비밀값(비밀번호 해시·TOTP 암호문·등록 코드)은 없다.
 */
interface AccountQuery {
    /** 최대 [AccountListPage.LIMIT]행(표시명·actorId 순). 더 있으면 [AccountListPage.truncated](S8 리뷰 nit-5 — 화면이 잘림을 알린다). */
    fun list(viewerActorId: String): AccountListPage?

    /**
     * `ROLE_REACTIVATE` 원인 후보(Q-S7-2 — 화면이 원인을 자유 입력받지 않게): [targetActorId]에서 아직 그 원인으로 정지된 역할이 있는 원인(재설정 승인 ID·
     * `episode:<n>`)과 그 역할. 조회자는 요청자 역할(ACTIVE SECURITY_ADMIN)만 — 아니면 null(웹 403). 원인의 유효성(실행된 재설정·참여자 배제)은 요청·승인 TX가 다시 판정한다.
     */
    fun reactivationCauses(viewerActorId: String, targetActorId: String): List<ReactivationCauseView>?
}

data class ReactivationCauseView(val cause: String, val roles: List<String>)

data class AccountListPage(val accounts: List<AccountListView>, val truncated: Boolean) {
    companion object {
        const val LIMIT = 500
    }
}

data class AccountListView(
    val actorId: String,
    /** SECURITY_ADMIN 조회자에게만(D-ADMIN1-017 — Q-S7-3). 그 밖 역할은 null(응답에서 빠진다). */
    val loginName: String?,
    val displayName: String,
    val status: String,
    val activeRoles: List<String>,
    val suspendedRoles: List<String>,
    val pendingPurpose: String?,
)
