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
 * CreatePayment 로컬 TX Use Case(CP-CONTRACT-001 §8–§11). 입력은 이미 형식 검증된 command이고(인증·shape·MID 일치·ID 생성은
 * 호출자 = [SubmitCreatePaymentUseCase]), 요청 fingerprint r1은 이 Use Case가 command 값으로 **저장된 버전에 맞춰** 계산한다(계약 §6.4·§7).
 * 이 Use Case는 PAYMENT DB 하나의 로컬 TX로 멱등 기록 → 주문 control → Payment → Outbox를 처리한다.
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
    val requestedAt: Instant,
    val correlationId: String,
    val newIdentifiers: NewPaymentIdentifiers,
    /** 요청한 인증 주체(Credential keyId, D-CPA-001). 새 Payment의 생성 행위자로 남는다(Auditability). */
    val actor: CredentialKeyId,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION_ID && correlationId.all { it in '!'..'~' }) {
            "correlationId must be 1..$MAX_CORRELATION_ID printable ASCII characters"
        }
        // orderName 규칙(CP-API-001 명세 §4.4): code point 1..100(영속 VARCHAR(100) utf8mb4와 같은 단위), 짝 없는 서로게이트·제어문자 거절.
        // web adapter가 같은 규칙으로 먼저 400을 만든다. 여기는 최후 방어선이다.
        require(orderName == null || OrderNameRule.isValid(orderName)) { "orderName violates the order name rule" }
    }

    private companion object {
        const val MAX_CORRELATION_ID = 128
    }
}

/**
 * 표시용 주문명 규칙(CP-API-001 명세 §4.4, 구현 결정 — 계약 §3 이월 L-8 확정). 길이 단위는 Unicode **code point** 1..[MAX_CODE_POINTS]이다:
 * 영속 컬럼 `order_name VARCHAR(100) CHARACTER SET utf8mb4`는 MySQL에서 문자(code point) 100개라 API 한계와 DB 한계가 정확히 같다
 * (UTF-16 단위면 보충 문자가 2로 세져 DB보다 좁고, UTF-8 byte 단위면 한글 100자가 거절된다). 거절: 짝 없는 서로게이트, 제어문자 Cc 전부
 * (탭·개행 포함), 줄·문단 구분자 U+2028·U+2029(로그 줄 분리), 양방향 embedding/override/isolate(U+202A–U+202E, U+2066–U+2069 — 표시 위조).
 * 그 밖의 형식 문자(이모지 결합 ZWJ 등)·공백만 있는 이름은 허용한다(trim·정규화 없음).
 */
object OrderNameRule {
    const val MAX_CODE_POINTS = 100

    fun isValid(name: String): Boolean {
        if (name.isEmpty() || !Utf8Text.isWellFormed(name)) return false
        if (name.codePointCount(0, name.length) > MAX_CODE_POINTS) return false
        return !LineSafeText.hasForbiddenCharacter(name)
    }
}

/**
 * 가맹점이 보낸 문자열 값의 거절 문자 규칙(CP-CONTRACT-001 §3.1, CP-API-001 명세 §4.3·§4.4): 제어문자 Cc 전부(탭·개행 포함), 줄·문단 구분자
 * U+2028·U+2029, 양방향 embedding/override/isolate U+202A–U+202E·U+2066–U+2069. 로그 줄 분리·표시 위조를 막는다(review round2 m-2).
 * orderName·merchantAccountId·merchantSiteId에 쓴다(merchantOrderId·멱등키는 D-CPC-002 ASCII 형식으로 이미 막힌다).
 */
object LineSafeText {
    fun hasForbiddenCharacter(value: String): Boolean = value.codePoints().anyMatch(::isForbidden)

    fun isForbidden(cp: Int): Boolean =
        Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp in 0x202A..0x202E || cp in 0x2066..0x2069
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

/**
 * 저장소 실패의 진단 값(n-3): MySQL vendor 오류 코드·SQLState·제약 업무 이름만. 값·메시지 원문은 담지 않는다. persistence adapter가 채우고
 * web 오류 처리기가 구조화 로그에 남긴다(web은 `java.sql`을 볼 수 없다 — ArchUnit 영속 기술 위치 규칙).
 */
data class StoreDiagnostic(val vendorCode: Int?, val sqlState: String?, val constraint: String? = null)

/** 기술 실패 분류(로그·지표 label — 낮은 cardinality). 응답 code는 실패 종류([CreatePaymentFailure] 하위 타입)로 정해진다. */
enum class FailureKind {
    LOCK_TIMEOUT,
    DEADLOCK,
    DB_UNAVAILABLE,
    RETRY_EXHAUSTED,
    TRANSACTION_UNAVAILABLE,
    UNSUPPORTED_FINGERPRINT_VERSION,
    IDENTIFIER_COLLISION,
    OUTCOME_UNKNOWN,
    OTHER,
}

/** 업무 결과가 아닌 기술 실패. 메시지에 요청 값을 넣지 않는다. [kind]·[diagnostic]은 로그용 분류다. */
sealed class CreatePaymentFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    abstract val kind: FailureKind

    /** 원인 사슬에서 찾은 저장소 진단 값(없으면 null). */
    val diagnostic: StoreDiagnostic?
        get() = generateSequence(cause) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH)
            .filterIsInstance<StoreDiagnosticSource>().firstNotNullOfOrNull { it.diagnostic }

    /**
     * 멱등 예약 단계의 잠금 대기 timeout(IDEMPOTENCY_REQUEST_IN_PROGRESS, retryable). 저장 없음(TX 전체 rollback).
     * 대기 대상은 **같은 키의 미커밋 요청**뿐 아니라 **인접 범위 갭 잠금**일 수 있다(CP-DB-001 review L-1): 멱등 예약은
     * `INSERT … ON DUPLICATE KEY UPDATE`라 기존 키의 중복 경로가 그 인덱스 레코드에 next-key 잠금을 잡고(READ COMMITTED에서도 중복 검사
     * 잠금은 남는다), 그 앞 갭에 다른 키를 넣는 요청이 기다릴 수 있다. 어느 쪽이든 결과·금융 의미는 같다(저장 없음, 같은 키로 재시도).
     * 예약 뒤 단계(권리 head·control·Payment)의 timeout은 이것이 아니라 [TemporarilyUnavailable]이다.
     */
    class RequestInProgress(cause: Throwable) : CreatePaymentFailure("same idempotency key is still in progress", cause) {
        override val kind: FailureKind = FailureKind.LOCK_TIMEOUT
    }

    /**
     * 교착 재시도 소진·(예약 뒤) 잠금 timeout·DB 일시 오류·지원하지 않는 저장 fingerprint 버전·식별자 충돌 반복. 이 요청의 TX는 commit되지
     * 않았다(TEMPORARILY_UNAVAILABLE, 같은 키로 재시도).
     */
    class TemporarilyUnavailable(cause: Throwable) : CreatePaymentFailure("payment store temporarily unavailable", cause) {
        override val kind: FailureKind = kindOf(cause)
    }

    /** 서버가 준 새 식별자가 기존 값과 충돌했다(사용자 payload 충돌 아님). rollback됨 — 새 식별자로 같은 키 재시도. */
    class IdentifierCollision(val identifier: GeneratedIdentifier) : CreatePaymentFailure("generated identifier collision: $identifier", null) {
        override val kind: FailureKind = FailureKind.IDENTIFIER_COLLISION
    }

    /** commit 결과를 확인하지 못했다. 새 키를 만들지 말고 같은 키로 다시 요청해 결과를 확인한다(초안 §5). */
    class OutcomeUnknown(cause: Throwable) : CreatePaymentFailure("commit outcome unknown", cause) {
        override val kind: FailureKind = FailureKind.OUTCOME_UNKNOWN
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 20

        fun kindOf(cause: Throwable): FailureKind = when (cause) {
            is FailureKindSource -> cause.failureKind
            is IdentifierCollision -> FailureKind.IDENTIFIER_COLLISION
            else -> FailureKind.OTHER
        }
    }
}

/** 원인 예외가 자기 실패 분류를 알려준다(저장소 실패·내부 신호). */
interface FailureKindSource {
    val failureKind: FailureKind
}

/** 원인 예외가 저장소 진단 값을 갖는다(persistence adapter가 채움). */
interface StoreDiagnosticSource {
    val diagnostic: StoreDiagnostic?
}

/** 서버가 생성해 넘긴 식별자 종류([NewPaymentIdentifiers]). */
enum class GeneratedIdentifier { PAYMENT_ID, PAYMENT_KEY, EVENT_ID }

/** 늦은 성공(확정 거절/만료 뒤 승인 성공) 보고 → 주문 사건 보류(CP-DOMAIN-001 M-3). 같은 보고가 여러 번 와도 보류는 하나다. */
interface ReportSuccessAfterTerminationUseCase {
    fun report(orderKey: OrderKey, paymentId: PaymentId, reportedAt: Instant)
}
