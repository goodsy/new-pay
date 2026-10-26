package com.paycore.admin.approval.domain

/**
 * `ROLE_REACTIVATE`의 원인 ID 형식(S6 리뷰 M-3 (a), D-ADMIN1-012 "나머지 역할은 일반 ROLE_REACTIVATE 2인 승인"):
 * - 재설정 승인 ID `apr_` + ULID(H-A — 원인 승인의 요청자·승인자 배제),
 * - 복구 회차 `episode:<번호>`(시스템 원인 — 원인 참여자 없음, 일반 2인 규칙만. 대상은 참여할 수 없다).
 * 원인으로 정지된 역할 = 그 역할의 최신 이벤트가 이 원인의 SUSPEND(역할 이벤트 재생 규칙).
 */
object ApprovalCause {
    private val APPROVAL = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
    private val EPISODE = Regex("^episode:[1-9][0-9]{0,17}$")

    fun isValid(cause: String): Boolean = APPROVAL.matches(cause) || EPISODE.matches(cause)

    fun isEpisode(cause: String): Boolean = EPISODE.matches(cause)
}
