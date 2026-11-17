package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.CancelOutcome
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.ConfirmOutcome
import com.paycore.payment.payment.application.provided.ConfirmRejection
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/** 멱등 범위(시도 §5, CP-CONTRACT-001 §7): (environment, 인증된 MID, operation, key). 입력 그대로 비교(D-CP-001). */
data class IdempotencyScope(
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val operation: IdempotentOperation,
    val key: IdempotencyKey,
)

/** 멱등 대상 연산. [code]는 영속 값(operation 컬럼)이며 바꾸지 않는다. */
enum class IdempotentOperation(val code: String) {
    CREATE_PAYMENT("CreatePayment"),

    /** AUTH-001 §4.8: confirm(승인 시작). 결과는 Authorization 참조(D-AUTH-004 — 재생 = 현재 결과) 또는 저장 거절. */
    CONFIRM_PAYMENT("ConfirmPayment"),

    /** CANCEL-001 §4.8: 가맹점 취소. 결과는 취소 참조(재생 = 그 취소의 현재 상태 — CC-05) 또는 저장 거절(Payment 참조). */
    CANCEL_PAYMENT("CancelPayment"),
}

/** 이 요청의 예약 내용. [expiresAt]은 [createdAt] + 보존 기간(D-CPC-004: 30일)이며 DB CHECK가 같은 규칙을 강제한다. */
data class IdempotencyReservation(
    val scope: IdempotencyScope,
    val recordId: IdempotencyRecordId,
    val requestFingerprint: RequestFingerprint,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** 저장된(커밋된) 요청 결과. 재생 응답 값은 [paymentId]/[referencePaymentId]가 가리키는 Payment의 생성 시 불변 값에서 다시 읽는다. */
sealed interface StoredRequestResult {
    /**
     * CreatePayment 성공. [merchantCode](MERCHANT-CODE-001 §6·C-37(MC-4) — D-CP-003 "저장된 생성 결과만"): 이 결과를 처음 만든 요청 때 읽은 그 MID의 가맹점 코드(출력 전용).
     * 재생은 이 저장값만 쓴다(응답 시점에 다시 조회하지 않는다). 코드 없는 MID·MC-4 이전 기록은 null → 응답 필드 생략. Payment 값·요청 지문·판정 입력이 아니다.
     */
    data class Succeeded(val outcome: CreatePaymentOutcome, val paymentId: PaymentId, val merchantCode: String? = null) : StoredRequestResult {
        init {
            require(merchantCode == null || MERCHANT_CODE.matches(merchantCode)) { "a stored merchant code has the merchant code format" }
        }

        private companion object {
            /** MASTER V13·PAYMENT V36 entitlement_head CHECK와 같은 문법(저장 결과를 다시 읽을 때 형식 밖 값은 결함 — fail-closed). */
            val MERCHANT_CODE = Regex("^[a-z][a-z0-9]{5,13}$")
        }
    }

    data class Rejected(val rejection: CreatePaymentRejection, val referencePaymentId: PaymentId) : StoredRequestResult

    /** confirm 성공(AUTH-001 §4.8): Authorization 참조 — 재생 응답은 그 승인의 **현재** 상태다. */
    data class ConfirmAccepted(val outcome: ConfirmOutcome, val paymentId: PaymentId, val authorizationId: AuthorizationId) : StoredRequestResult

    /** confirm 업무 거절(AUTH-001 §4.8): Payment 참조 + 고정 분류 상세(예: PAYMENT_NOT_ELIGIBLE의 reason). */
    data class ConfirmRejected(val rejection: ConfirmRejection, val paymentId: PaymentId, val detail: String?) : StoredRequestResult {
        init {
            require(detail == null || DETAIL.matches(detail)) { "rejection detail must be a fixed upper-case classification" }
        }

        private companion object {
            val DETAIL = Regex("^[A-Z_]{1,48}$")
        }
    }
}

/** 취소 성공(CANCEL-001 §4.8): 새 시작(ACCEPTED) 또는 같은 merchantCancelId·같은 내용의 기존 취소(EXISTING_CANCELLATION) 참조. */
data class StoredCancelAccepted(val outcome: CancelOutcome, val paymentId: PaymentId, val cancellationId: CancellationId) : StoredRequestResult

/** 취소 업무 거절(CANCEL-001 §4.8·§4.9 — 저장·재생, Payment 참조). */
data class StoredCancelRejected(val rejection: CancelRejection, val paymentId: PaymentId) : StoredRequestResult

/** 이미 있던 멱등 기록. [result]가 null이면 커밋된 예약(PENDING)이라는 뜻이며 정상 흐름에서는 생길 수 없다(같은 TX에서 결과를 쓴다). */
data class StoredIdempotencyRecord(
    val recordId: IdempotencyRecordId,
    val requestFingerprint: RequestFingerprint,
    val result: StoredRequestResult?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    /** 보존 기간이 끝났다(삭제 전이라도 재생하지 않고 새 요청으로 처리한다 — D-CPC-004). */
    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
}

sealed interface IdempotencyReservationOutcome {
    /** 이 TX가 새 기록(PENDING)을 만들었고 그 행을 잠그고 있다. */
    data object Reserved : IdempotencyReservationOutcome

    /** 같은 범위의 기록이 이미 있다(다른 TX가 커밋한 결과). 이 TX가 그 행을 잠그고 있다. */
    data class Existing(val record: StoredIdempotencyRecord) : IdempotencyReservationOutcome
}

/**
 * 멱등 요청 기록 저장소(PAYMENT DB). 모든 메서드는 호출자의 로컬 TX 안에서만 동작한다(TX 없으면 거부).
 *
 * 경쟁 규칙: [reserveOrLock]은 범위 UNIQUE에 먼저 INSERT를 시도하고(SELECT 후 INSERT 아님) 이미 있으면 그 행의 잠금을 기다려 잡는다.
 * 같은 키 동시 요청은 여기서 직렬화되고 먼저 커밋된 결과가 기준이다(CP-CONTRACT-001 §8). 잠금 대기 timeout은
 * [PersistenceFailure.LockTimeout]이다(앞 요청이 아직 커밋하지 않음 → IDEMPOTENCY_REQUEST_IN_PROGRESS).
 */
interface IdempotencyRecordStore {
    fun reserveOrLock(reservation: IdempotencyReservation): IdempotencyReservationOutcome

    /** [reserveOrLock]이 Existing으로 잠근 만료 기록을 이 요청으로 갱신한다(새 recordId·fingerprint·시각, 결과는 PENDING). */
    fun renewExpired(reservation: IdempotencyReservation)

    /** 이 TX가 잠근 기록에 결과를 쓴다. */
    fun complete(scope: IdempotencyScope, result: StoredRequestResult, completedAt: Instant)
}
