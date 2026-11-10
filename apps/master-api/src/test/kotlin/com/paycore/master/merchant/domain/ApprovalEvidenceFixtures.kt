package com.paycore.master.merchant.domain

import java.time.Duration
import java.time.Instant

/**
 * 승인 근거 Named Fixture(MERCHANT-OPS-002 §8.2 B2 — src/test 전용, Test Fixture Policy). 무효 의도는 이름에 드러낸다: [sameActor](요청자 = 승인자), [windowExceeded](최대 창 + 1ms),
 * [expiredAt](주어진 시각에 이미 기한 — 배타 끝). 날짜·창 경계는 명시 값이다(자동 생성 금지). 무작위는 approvalId 형식 문자열(ULID)에만 쓴다.
 * 행위자는 ADMIN 정규형 `admin:adm_<ULID 대문자>`(시험 값 — 실제 계정 아님). 지문은 형식만 맞는 자리값이 기본이다(MASTER 지문 재계산은 web parser 단계 — 실행기는 저장만 한다).
 */
object ApprovalEvidenceFixtures {
    /** 요청자(OPERATOR)·승인자(APPROVER)·제3 행위자(예: Revision 등록자). */
    val REQUESTER = ActorRef(admin("RQ1"))
    val APPROVER = ActorRef(admin("AP1"))
    val OTHER = ActorRef(admin("RG1"))

    /** 시험 승인 시각: application 시험 clock t0(2026-10-09T02:00Z)보다 1분 앞, 기한은 승인 + 1시간(창 PT24H 안). */
    val APPROVED_AT: Instant = Instant.parse("2026-10-09T01:59:00.000Z")
    val EXECUTE_BEFORE: Instant = APPROVED_AT.plus(Duration.ofHours(1))

    /** Demo 창(C-19 — 운영값 아님): 최대 실행 창 PT24H, 시계 앞섬 PT1M. */
    val DEMO_WINDOW = ApprovalWindow(Duration.ofHours(24), Duration.ofMinutes(1))

    const val EVIDENCE_REF = "CONTRACT-DOC/2026-001#p1"
    val PLACEHOLDER_FINGERPRINT = "mop1:" + "0".repeat(64)

    fun admin(tag: String): String = "admin:adm_01JC" + tag.padStart(22, '0')

    fun approvalId(): String = "apr_" + MasterFixture.ulid()

    @Suppress("LongParameterList")
    fun distinctActors(
        approvalId: String = approvalId(),
        requestedBy: ActorRef = REQUESTER,
        approvedBy: ActorRef = APPROVER,
        approvedAt: Instant = APPROVED_AT,
        executeBefore: Instant = EXECUTE_BEFORE,
        evidenceRef: String = EVIDENCE_REF,
        fingerprint: String = PLACEHOLDER_FINGERPRINT,
    ): ApprovalEvidence = ApprovalEvidence(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, EvidenceRef(evidenceRef), fingerprint)

    /** 무효 의도: 요청자 = 승인자(ADMIN 승인함 우회 신호 — 판정 1). */
    fun sameActor(approvalId: String = approvalId()): ApprovalEvidence = distinctActors(approvalId, approvedBy = REQUESTER)

    /** 무효 의도: 기한 − 승인 시각 = 최대 창 + 1ms(판정 2). */
    fun windowExceeded(approvalId: String = approvalId(), window: ApprovalWindow = DEMO_WINDOW): ApprovalEvidence =
        distinctActors(approvalId, executeBefore = APPROVED_AT.plus(window.maxExecution).plusMillis(1))

    /** 무효 의도: [at]이 곧 기한(배타 끝) — [at] 이후의 잠금 뒤 시각에서 만료(판정 4). 승인 시각은 기한 1시간 전. */
    fun expiredAt(at: Instant, approvalId: String = approvalId()): ApprovalEvidence =
        distinctActors(approvalId, approvedAt = at.minus(Duration.ofHours(1)), executeBefore = at)
}
