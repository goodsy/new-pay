package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 2인 승인 명령의 승인 근거(MERCHANT-OPS-002 §9.3 — D-MOPS-007 ①·D-MOPS-008 ①, D-ADMIN-007). ADMIN 승인함이 만든 승인 하나(approvalId)에 대한 요청자·승인자·승인 시각·
 * 실행 기한(배타)·업무 근거 참조·`mop1` 지문을 담는 순수 값이다(JPA Entity와 분리 — Mapper는 persistence adapter).
 *
 * MASTER가 다시 판정하는 것(§9.3 표): 1 요청자 = 승인자 → [MasterRejection.APPROVAL_SAME_ACTOR], 2 실행 창 위반 → [MasterRejection.APPROVAL_WINDOW_INVALID]
 * ([admit] — 순서 1 → 2), 4 잠금 뒤 시각 ≥ 기한 → [MasterRejection.APPROVAL_EXPIRED]([requireNotExpired]). 3(approvalId 1회 사용)은 Application이 저장소로 판정한다.
 * 행위자는 정규형 문자열 그대로 비교한다(대소문자 변형은 형식에서 거부 — 문자열 비교가 신원 비교). MASTER는 이 값이 실제 ADMIN 계정·TOTP 승인인지 검증할 수 없다(L-10).
 *
 * toString에는 approvalId만 넣는다(행위자·근거 참조·지문은 로그에 남기지 않는다 — §9.7).
 */
class ApprovalEvidence(
    val approvalId: String,
    val requestedBy: ActorRef,
    val approvedBy: ActorRef,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: EvidenceRef,
    val fingerprint: String,
) {
    init {
        require(APPROVAL_ID.matches(approvalId)) { "approvalId must match apr_<ULID>" }
        require(ACTOR.matches(requestedBy.value) && ACTOR.matches(approvedBy.value)) { "approval actors must be canonical admin:adm_<ULID>" }
        require(approvedAt.nano % NANOS_PER_MILLI == 0 && executeBefore.nano % NANOS_PER_MILLI == 0) { "approval instants have millisecond precision" }
        require(FINGERPRINT.matches(fingerprint)) { "approval fingerprint must match mop1:<64 hex>" }
    }

    /** 판정 1 → 2(§9.3). [now] = MASTER 멱등 기록 예약 시각. */
    fun admit(now: Instant, window: ApprovalWindow) {
        if (requestedBy == approvedBy) throw MasterRuleViolation(MasterRejection.APPROVAL_SAME_ACTOR)
        val windowInvalid = !executeBefore.isAfter(approvedAt) ||
            executeBefore.isAfter(approvedAt.plus(window.maxExecution)) ||
            approvedAt.isAfter(now.plus(window.maxClockSkew))
        if (windowInvalid) throw MasterRuleViolation(MasterRejection.APPROVAL_WINDOW_INVALID)
    }

    /** 판정 4(m-4): 업무 잠금 뒤 읽은 시각 [lockedNow]가 기한(배타 끝) 이상이면 만료. */
    fun requireNotExpired(lockedNow: Instant) {
        if (isDueAt(lockedNow)) throw MasterRuleViolation(MasterRejection.APPROVAL_EXPIRED)
    }

    /** 기한 확인(§9.4 ③·④): [now]가 기한(배타 끝) 이상이면 true. */
    fun isDueAt(now: Instant): Boolean = !now.isBefore(executeBefore)

    /** 값 객체 — 모든 필드가 같으면 같은 근거다(같은 저장 바이트에서 해석한 두 명령이 같다). */
    override fun equals(other: Any?): Boolean = this === other || (
        other is ApprovalEvidence && approvalId == other.approvalId && requestedBy == other.requestedBy && approvedBy == other.approvedBy &&
            approvedAt == other.approvedAt && executeBefore == other.executeBefore && evidenceRef == other.evidenceRef && fingerprint == other.fingerprint
        )

    override fun hashCode(): Int = listOf(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fingerprint).hashCode()

    override fun toString(): String = "ApprovalEvidence(approvalId=$approvalId)"

    companion object {
        val APPROVAL_ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        val ACTOR = Regex("^admin:adm_[0-9A-HJKMNP-TV-Z]{26}$")
        val FINGERPRINT = Regex("^mop1:[0-9a-f]{64}$")
        private const val NANOS_PER_MILLI = 1_000_000
    }
}
