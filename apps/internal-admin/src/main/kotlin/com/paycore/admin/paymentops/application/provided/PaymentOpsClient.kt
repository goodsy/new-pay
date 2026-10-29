package com.paycore.admin.paymentops.application.provided

/**
 * PAYMENT 내부 API 클라이언트(ADMIN-PAYMENT-OPS-001 §4.1·§4.7 — 서명된 호출, **DB TX 밖에서만**: TX 안에서 부르면 `IllegalStateException`). 호출자는
 * `approval.application`의 지정 서비스와 paymentops 자신뿐(ArchitectureSpec). 외부 호출 permit은 용도별로 분리한다(L-8 — [CallPurpose]).
 * - [target]: 대상 조회(비권위 — PAYMENT가 실행 때 잠금 아래 다시 검사).
 * - [execute]: 승인 TX1이 저장한 실행 본문 바이트를 그대로 POST하고 응답을 §4.15 결과로 분류한다(재전송도 같은 바이트).
 */
interface PaymentOpsClient {
    fun target(paymentId: String, purpose: CallPurpose): TargetResult

    fun execute(approvalId: String, body: ByteArray): ExecutionResult

    /** 기록 결정 조회(§4.3 `GET /internal/v1/admin/operator-cancellations/{approvalId}` — 회복 job 1단계, execution pool). */
    fun decision(approvalId: String): DecisionResult

    /**
     * 기한 확인 전용 호출(D-OPS-009, §4.4.5 — 본문 = 그 승인의 **저장된 실행 본문 그대로**, 경로가 달라 실행 서명과 바꿔 쓸 수 없다). PAYMENT는 자기 시각으로 기한이 지났고
     * 결정이 없을 때만 `REJECTED(APPROVAL_EXECUTION_EXPIRED)`를 기록하고 **취소를 시작하지 않는다**.
     */
    fun confirmDeadline(approvalId: String, body: ByteArray): DecisionResult
}

sealed interface DecisionPage {
    data class Page(val items: List<DecisionListItem>, val nextWatermark: Long, val serverTime: java.time.Instant) : DecisionPage

    data class Unavailable(val reason: String) : DecisionPage
}

/** 결정 목록 한 행(사유·근거 문구 없음 — §4.17). [decision] STARTED·REJECTED, [origin] EXECUTION_COMMAND·DEADLINE_CONFIRMATION. */
data class DecisionListItem(
    val seq: Long,
    val approvalId: String,
    val decision: String,
    val code: String?,
    val cancellationId: String?,
    val content: DecisionContent,
    val recordedAt: java.time.Instant,
    val origin: String,
)

/**
 * 기록 결정(§4.4·§4.4.5 — 계약 golden ③ `decisionResponses`·`deadlineResponses`).
 * - [Recorded]: `STARTED`(cancellationId) 또는 `REJECTED`(code). [content]는 ADMIN 내용 대조용 공통 필드(L-b), [confirmed]는 기한 확인 호출이 이번에 EXPIRED를 기록했는지.
 * - [None]: 결정 없음(기한 확인이면 PAYMENT 시각상 기한 전).
 * - [Unavailable]: 호출 실패·permit 없음·응답 해석 불가 — 상태 유지, 다음 주기.
 */
sealed interface DecisionResult {
    data class Recorded(val decision: String, val code: String?, val cancellationId: String?, val content: DecisionContent, val confirmed: Boolean) : DecisionResult

    data object None : DecisionResult

    data class Unavailable(val reason: String) : DecisionResult
}

/** 결정의 공통 내용(rev 0.3 L-b) — ADMIN 명령·승인·실행 행과 대조한다. */
data class DecisionContent(
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedTargetFingerprint: String,
)

/** 외부 호출 용도(permit pool): 승인 A 단계·요청 전 조회 [APPROVAL], 화면 조회 [LOOKUP](속도 제한 별도). 실행 POST는 `execution` pool. */
enum class CallPurpose { APPROVAL, LOOKUP }

/** PAYMENT 대상 조회 응답(§4.3). 금액은 minor 단위, [cancellableMinor] ≥ 0(F-1), [balanceRevision]은 반환 잔액 revision(없으면 0). */
data class PaymentTargetView(
    val paymentId: String,
    val paymentStatus: String,
    val cancellable: Boolean,
    val currency: String?,
    val authorizedMinor: Long?,
    val cancellableMinor: Long,
    val balanceRevision: Long,
    val onHold: Boolean,
    val inFlightCancel: Boolean,
)

sealed interface TargetResult {
    data class Found(val view: PaymentTargetView) : TargetResult

    /** PAYMENT 404 `PAYMENT_NOT_FOUND`(형식 오류 포함). */
    data object NotFound : TargetResult

    /** 호출 실패·permit 없음·응답 해석 불가 등 — 저장 없이 503(요청)·상태 불변(승인 A). [reason]은 고정 분류. */
    data class Unavailable(val reason: String) : TargetResult
}

/**
 * 실행 POST 결과 분류(§4.15, I-21 — 계약 golden ③ `responseCases`):
 * - [Started]: 200(새·재생) — ADMIN `EXECUTED`(owner_reference = cancellationId).
 * - [OwnerRejected]: 409 업무 거절 code·404 `PAYMENT_NOT_FOUND`·400 형식 code — ADMIN `OWNER_REJECTED(code)`. [integrityAlert]: 400 형식(ADMIN 결함 — golden ③),
 *   [operationalAlert]: `APPROVAL_WINDOW_INVALID`(설정 순서 위반 — L-6).
 * - [Unknown]: 409 `APPROVAL_CONFLICT`·`APPROVAL_IDENTITY_MISMATCH`([integrityFlag] = 그 code, 무결성 경보), 401([alert] — 운영 경보: 키·시각)·413·415·503·5xx·timeout·연결 실패·응답 해석 불가·
 *   본문 상한 초과·permit 없음 — ADMIN `EXECUTION_UNKNOWN`(D-OPS-003: 확정 응답만 종료).
 */
sealed interface ExecutionResult {
    data class Started(val cancellationId: String, val existing: Boolean) : ExecutionResult

    data class OwnerRejected(val code: String, val integrityAlert: Boolean, val operationalAlert: Boolean = false) : ExecutionResult

    data class Unknown(val failure: String, val integrityFlag: String?, val alert: Boolean) : ExecutionResult
}
