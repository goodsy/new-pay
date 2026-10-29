package com.paycore.admin.approval.application.required

/**
 * 소유 서비스 운영 지표 Port(ADMIN-PAYMENT-OPS-001 §6·§4.16 — 구현 `adapter.integration.MicrometerOwnerOpsMetrics`). label은 고정 분류값만(승인 ID·paymentId·actorId 없음).
 * - [integrityAlert]: `paycore.admin.ops.integrity_alert{code}` — 파생 409(`APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH`), 400 형식 code, 401 `UNAUTHENTICATED`,
 *   `APPROVAL_WINDOW_INVALID`. **1건이라도 경보**(조사 대상, 수동 종료 없음 — D-OPS-007).
 * `paycore.admin.ops.unresolved{status}`·`unknown.oldest_age_seconds`·`resend*`·`outcome_divergence`는 회복 job(S6)이 더한다.
 */
interface OwnerOpsMetrics {
    fun integrityAlert(code: String)

    /** `paycore.admin.ops.resend{result}` — 회복 job이 보낸 명령(실행 POST `EXECUTE_*`·기한 확인 `DEADLINE_*`)의 결과 분류. */
    fun resend(result: String)

    /** `paycore.admin.ops.resend_halted{reason}` — 참여자 정지·역할 상실로 재전송 중단(D-OPS-006, 승인당 1회). */
    fun resendHalted(reason: String)

    /** `paycore.admin.ops.outcome_divergence` — 이미 종료된 승인에 다른 결과가 도착(§4.13 D). */
    fun outcomeDivergence()

    /** `paycore.admin.ops.unresolved{status}` gauge — 미해결(EXECUTING·EXECUTION_UNKNOWN) 수(회복 실행마다 갱신). */
    fun unresolved(status: String, count: Long)

    /** `paycore.admin.ops.unknown.oldest_age_seconds` gauge — 가장 오래된 EXECUTION_UNKNOWN의 승인 이후 경과(경보 Demo 15분 — 운영 설정). */
    fun unknownOldestAge(seconds: Long)

    /** `paycore.admin.ops.recon.run{result}` — 대사 실행 결과(`OK`·`PAYMENT_UNAVAILABLE`·`ERROR`). */
    fun reconRun(result: String)

    /** `paycore.admin.ops.recon.finding{kind}` — 새로 기록한 발견. */
    fun reconFinding(kind: String)

    /** `paycore.admin.ops.recon.finding_duplicate` — 이미 기록된 발견(UNIQUE 1062 — 정상, 경보 아님). */
    fun reconFindingDuplicate()

    /** `paycore.admin.ops.recon.lag_seconds` gauge — 마지막 성공 실행 이후 경과(경보 Demo 30분). */
    fun reconLag(seconds: Long)

    /** 종류별 열린 발견 수 gauge `paycore.admin.ops.recon.open_findings{kind}`(I-118 — 6종 모두, 없으면 0). */
    fun openFindings(counts: Map<String, Long>)

    /** 근거·사유 조회 속도 제한(429) `paycore.admin.approval.justification_rate_limited`(I-131). */
    fun justificationRateLimited()
}
