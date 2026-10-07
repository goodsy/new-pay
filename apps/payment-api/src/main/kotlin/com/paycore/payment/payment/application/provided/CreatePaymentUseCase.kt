package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.Utf8Text
import java.time.Instant

/**
 * CreatePayment 로컬 TX Use Case(CP-CONTRACT-001 §8–§11). 입력은 이미 형식 검증된 command이고(인증·shape·MID 일치·r1 계산·ID 생성은
 * 호출자 = CP-API-001), 이 Use Case는 PAYMENT DB 하나의 로컬 TX로 멱등 기록 → 주문 control → Payment → Outbox를 처리한다.
 * 외부 호출 없음. 응답은 commit 뒤에 만들어진다. 오류 HTTP 매핑은 CP-API-001.
 */
interface CreatePaymentUseCase {
    /**
     * eligibility는 이 Use Case가 required Port(`PaymentEligibilityPort`, 계약 §13)로 **command 값**(environment·인증 MID·Site·통화·
     * 결제수단)과 서버 시각으로 평가한다. 호출자가 판정을 주입할 수 없다(다른 Site·통화로 평가한 결과를 쓸 수 없음). 멱등 기록을 잡은 뒤,
     * 주문 판정 전에 같은 TX 안에서 한 번 평가하고(계약 §3.2 순서), 같은 키의 저장 결과를 재생할 때는 평가하지 않는다.
     * Eligible이 아니면 TX 전체를 rollback하고(멱등 결과 저장 안 함, 계약 §9 eligibility 행) [CreatePaymentResult.EligibilityRefused]를 준다.
     *
     * @throws CreatePaymentFailure 기술 실패(업무 결과 아님). 결과가 저장되지 않았거나(rollback) 저장 여부를 같은 키로 다시 확인해야 한다.
     */
    fun create(command: CreatePaymentCommand): CreatePaymentResult
}

/** 서버가 생성해 넘기는 새 식별자(생성기·형식은 CP-API-001). 새 Payment를 만들지 않는 경로에서는 쓰이지 않는다. */
data class NewPaymentIdentifiers(
    val paymentId: PaymentId,
    val paymentKey: PaymentKey,
    val idempotencyRecordId: IdempotencyRecordId,
    val eventId: EventId,
)

/**
 * 검증된 CreatePayment 입력. [orderKey]의 MID는 인증 MID(body MID와 같음을 호출자가 확인), environment는 Credential에서 온다.
 * [requestedAt]은 서버 clock(ms로 잘라 저장한다). [orderName]은 표시용(고정 입력 아님, 이벤트 payload 제외).
 */
data class CreatePaymentCommand(
    val orderKey: OrderKey,
    val orderIntent: OrderIntent,
    val paymentMethod: PaymentMethod,
    val orderName: String?,
    val idempotencyKey: IdempotencyKey,
    val requestFingerprint: RequestFingerprint,
    val requestedAt: Instant,
    val correlationId: String,
    val newIdentifiers: NewPaymentIdentifiers,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION_ID && correlationId.all { it in '!'..'~' }) {
            "correlationId must be 1..$MAX_CORRELATION_ID printable ASCII characters"
        }
        // orderName 형식(길이 단위·제어문자)은 CP-API-001(L-8). 여기서는 영속 컬럼(VARCHAR(100) = code point 100)과 JDBC `?` 치환 방지만.
        require(orderName == null || (orderName.isNotEmpty() && orderName.codePointCount(0, orderName.length) <= MAX_ORDER_NAME)) {
            "orderName must be 1..$MAX_ORDER_NAME code points when present"
        }
        require(orderName == null || Utf8Text.isWellFormed(orderName)) { "orderName must be well-formed Unicode text" }
    }

    private companion object {
        const val MAX_CORRELATION_ID = 128
        const val MAX_ORDER_NAME = 100
    }
}

/** 생성 결과의 Payment 생성 시점 값(D-CP-003). 현재 상태는 넣지 않는다 — [lifecycleAtCreation]은 항상 REQUESTED. */
data class CreatedPayment(
    val paymentId: PaymentId,
    val paymentKey: PaymentKey,
    val lifecycleAtCreation: PaymentStatus,
    val paymentCreatedAt: Instant,
    val merchantOrderId: MerchantOrderId,
    val money: Money,
)

/**
 * 업무 결과. [Accepted]·[Rejected]는 commit된(또는 저장된 결과를 재생한) 결과, [IdempotencyKeyReused]·[EligibilityRefused]는 아무것도
 * 저장하지 않은 결과다. [replayed] = 같은 키·같은 내용의 저장 결과 재생.
 */
sealed interface CreatePaymentResult {
    val replayed: Boolean

    data class Accepted(val outcome: CreatePaymentOutcome, val payment: CreatedPayment, override val replayed: Boolean) : CreatePaymentResult

    /** 409 업무 거절(D-CPC-007: 저장·30일 재생). [referencePaymentId]는 같은 MID 자기 주문의 Payment다. */
    data class Rejected(
        val rejection: CreatePaymentRejection,
        val referencePaymentId: PaymentId,
        override val replayed: Boolean,
    ) : CreatePaymentResult

    /** 같은 키·다른 내용(IDEMPOTENCY_KEY_REUSED). 기존 결과·내용을 노출하지 않으며 아무것도 바꾸지 않았다. */
    data object IdempotencyKeyReused : CreatePaymentResult {
        override val replayed: Boolean = false
    }

    /**
     * eligibility 평가가 통과하지 못했다(계약 §9 eligibility 행 404/422/503). TX 전체가 rollback되어 멱등 기록·Payment·Outbox 어느 것도
     * 남지 않았다 — 같은 키 재요청은 다시 평가된다. 재생 결과가 아니다.
     */
    data class EligibilityRefused(val refusal: EligibilityRefusal) : CreatePaymentResult {
        override val replayed: Boolean = false
    }
}

/**
 * eligibility 거절 분류(계약 §13 `EligibilityResult`의 Eligible이 아닌 세 종류). 응답에는 고정 code만 쓰고 내부 버전·정책 값은 내보내지
 * 않는다(§9.1). HTTP 매핑은 CP-API-001.
 */
sealed interface EligibilityRefusal {
    /** Site 없음·다른 Merchant/MID의 Site·Assignment 무효(없음과 권한 없음을 구분하지 않음, 404 MERCHANT_SITE_NOT_FOUND). */
    data object NotFoundOrForbidden : EligibilityRefusal

    /** 통화·결제수단 미활성, 계약 효력 밖, MID 신규 결제 중지(422 PAYMENT_NOT_ELIGIBLE). */
    data class NotEligible(val reason: NotEligibleReason) : EligibilityRefusal

    /** Snapshot 누락·만료·확인 불가 버전(fail-closed, 503 ELIGIBILITY_UNAVAILABLE, 같은 키로 재시도). */
    data class Unavailable(val reason: EligibilityUnavailableReason) : EligibilityRefusal
}

/** 업무 결과가 아닌 기술 실패. 메시지에 요청 값을 넣지 않는다. */
sealed class CreatePaymentFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    /** 같은 키의 앞 요청이 아직 커밋하지 않아 잠금 대기 시간이 지났다(IDEMPOTENCY_REQUEST_IN_PROGRESS, retryable). 저장 없음. */
    class RequestInProgress(cause: Throwable) : CreatePaymentFailure("same idempotency key is still in progress", cause)

    /** 교착 재시도 소진·잠금 timeout·DB 일시 오류. 이 요청의 TX는 commit되지 않았다(TEMPORARILY_UNAVAILABLE, 같은 키로 재시도). */
    class TemporarilyUnavailable(cause: Throwable) : CreatePaymentFailure("payment store temporarily unavailable", cause)

    /** 서버가 준 새 식별자가 기존 값과 충돌했다(사용자 payload 충돌 아님). rollback됨 — 새 식별자로 같은 키 재시도. */
    class IdentifierCollision(val identifier: GeneratedIdentifier) : CreatePaymentFailure("generated identifier collision: $identifier", null)

    /** commit 결과를 확인하지 못했다. 새 키를 만들지 말고 같은 키로 다시 요청해 결과를 확인한다(초안 §5). */
    class OutcomeUnknown(cause: Throwable) : CreatePaymentFailure("commit outcome unknown", cause)
}

/** 서버가 생성해 넘긴 식별자 종류([NewPaymentIdentifiers]). */
enum class GeneratedIdentifier { PAYMENT_ID, PAYMENT_KEY, EVENT_ID }

/** 늦은 성공(확정 거절/만료 뒤 승인 성공) 보고 → 주문 사건 보류(CP-DOMAIN-001 M-3). 같은 보고가 여러 번 와도 보류는 하나다. */
interface ReportSuccessAfterTerminationUseCase {
    fun report(orderKey: OrderKey, paymentId: PaymentId, reportedAt: Instant)
}
