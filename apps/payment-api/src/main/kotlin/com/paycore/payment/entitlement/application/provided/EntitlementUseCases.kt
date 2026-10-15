package com.paycore.payment.entitlement.application.provided

import com.paycore.payment.entitlement.domain.InboxOutcome
import com.paycore.payment.entitlement.domain.IncomingSnapshot
import java.time.Instant

/**
 * MASTER 권리 Snapshot 수신(Inbox, MASTER-SNAPSHOT-001 명세 §5.1). 서명 검증·봉투 해석은 호출자(web adapter)가 TX 밖에서 끝낸다. 이 use case는
 * PAYMENT 로컬 TX 하나에서 head 잠금 → Inbox (consumer, eventId) 조회 → 적용 규칙 → Inbox 기록 + 업무 반영(head·revision별 내용·MID 소속)을
 * 함께 커밋하고, **커밋 뒤에만** 결과를 돌려준다(ACK = 내구 커밋). 커밋 단계 실패는 결과를 돌려주지 않는다([ReceiveFailure]) — MASTER가 같은
 * eventId로 다시 보내면 DUPLICATE 또는 APPLIED로 수렴한다.
 */
interface ReceiveEntitlementSnapshotUseCase {
    fun receive(envelope: InboundSnapshotEnvelope): ReceiveResult
}

/** 해석된 봉투 v1. [rawBody]는 받은 본문(원문 보존), [rawSha256]은 그 바이트의 SHA-256(소문자 hex). */
data class InboundSnapshotEnvelope(
    val eventType: String,
    val snapshot: IncomingSnapshot,
    val rawBody: String,
    val rawSha256: String,
)

sealed interface ReceiveResult {
    /**
     * 내구 커밋된 결과(새 처리 또는 같은 eventId 재전송의 DUPLICATE). [appliedRevision] = 지금 head revision. DUPLICATE이면 [originalOutcome] =
     * 처음 처리 때 저장한 outcome(review m-3 — 예: 처음이 UNSUPPORTED였으면 MASTER가 fail-closed 효력 확인에 쓴다).
     */
    data class Stored(val consumer: String, val outcome: InboxOutcome, val appliedRevision: Long, val originalOutcome: InboxOutcome? = null) : ReceiveResult

    /** 같은 eventId가 다른 본문으로 왔다(계약 오류 — 저장 없음, 수신 adapter는 409). */
    data object EventIdConflict : ReceiveResult
}

/** 결과를 알 수 없거나 일시적으로 처리하지 못했다(수신 adapter는 5xx — MASTER가 같은 eventId로 다시 보낸다). */
class ReceiveFailure(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * 결제 생성 판정(명세 §5.3, §6). **호출자의 PAYMENT 로컬 TX에 참여한다**(MANDATORY — CreatePayment가 멱등 기록 잠금을 쥔 채 부른다). PAYMENT DB의
 * 로컬 투영만 읽고(쓰기·외부 호출·MASTER 조회 없음), 프로세스 안 캐시를 두지 않는다(매 평가 DB를 읽는다 — 그래서 Inbox 커밋 = 모든 인스턴스 적용,
 * 명세 §3.4-3). head는 `FOR SHARE`로 읽는다.
 */
interface EntitlementEvaluationQuery {
    /** @throws EntitlementEvaluationFailure 저장소 일시 실패(교착·잠금 대기 timeout·연결). 판정 결과가 아니다 — 호출자 TX는 rollback해야 한다. */
    fun evaluate(request: EligibilityRequest, evaluatedAt: Instant): EligibilityDecision
}

/**
 * 판정 중 저장소 일시 실패(공개 타입, CP-API-001 G-1). 내부 `EntitlementPersistenceFailure`(application.required)는 다른 모듈이 볼 수 없어
 * 호출자가 분류하지 못했다(→ 500). 호출자(payment의 eligibility adapter)는 이것을 자기 저장소 실패 분류로 옮긴다: 교착은 재시도, head `FOR SHARE`
 * 잠금 대기 timeout은 같은 멱등키 경합이 아니므로 일시 불가(503). 메시지에 값을 넣지 않는다.
 */
sealed class EntitlementEvaluationFailure(message: String, cause: Throwable, val vendorCode: Int?, val sqlState: String?) :
    RuntimeException(message, cause) {
    class Deadlock(cause: Throwable, vendorCode: Int? = null, sqlState: String? = null) :
        EntitlementEvaluationFailure("entitlement evaluation deadlock", cause, vendorCode, sqlState)

    class LockTimeout(cause: Throwable, vendorCode: Int? = null, sqlState: String? = null) :
        EntitlementEvaluationFailure("entitlement evaluation lock wait timeout", cause, vendorCode, sqlState)

    class Unavailable(cause: Throwable, vendorCode: Int? = null, sqlState: String? = null) :
        EntitlementEvaluationFailure("entitlement store unavailable", cause, vendorCode, sqlState)
}

/** 판정 입력(다른 모듈이 쓰는 공개 타입 — domain 타입을 노출하지 않는다). environment는 받지 않는다(D-MS-001). */
data class EligibilityRequest(val mid: String, val siteId: String, val paymentMethod: String, val currency: String)

/** 판정 결과(공개 타입). CreatePayment Port adapter가 계약 §13 EligibilityResult로 1:1로 옮긴다. */
sealed interface EligibilityDecision {
    data class Allowed(val snapshotVersion: String, val currency: String, val minorUnit: Int, val validUntil: Instant) : EligibilityDecision

    data object NotFoundOrForbidden : EligibilityDecision

    data class Refused(val reason: EligibilityRefusalReason) : EligibilityDecision

    data class Unavailable(val reason: EligibilityUnavailableCause) : EligibilityDecision
}

/** CP계약 §13 NotEligible 고정 4종과 같은 이름. */
enum class EligibilityRefusalReason { CURRENCY_NOT_ENABLED, METHOD_NOT_ENABLED, CONTRACT_NOT_EFFECTIVE, MERCHANT_SUSPENDED }

/** CP계약 §13 Unavailable 3종과 같은 이름(fail-closed). */
enum class EligibilityUnavailableCause { SNAPSHOT_MISSING, SNAPSHOT_EXPIRED, VERSION_UNSUPPORTED }

/** 권리 투영 신선도(운영 조회·지표, jOOQ). */
interface EntitlementFreshnessQuery {
    fun overview(): FreshnessOverview
}

data class FreshnessOverview(
    val heads: Long,
    val blockedHeads: Long,
    val expiredHeads: Long,
    val minLeaseRemainingMillis: Long?,
)
