package com.paycore.financial.paymentfact.application.provided

import java.time.Instant
import java.time.LocalDate

/**
 * 매입 모듈(`acquiring`)에 주는 결제 사실 조회(ACQUIRING-001 §4 — 매입 후보의 원천은 FINANCIAL 투영, D-ACQ-001·D-ACQ-002). **호출자 TX 필수**(같은 FINANCIAL DB의
 * 로컬 TX — 매입 봉인 TX 안에서 부른다). 매입 모듈은 결제 사실 표를 직접 읽지 않고 이 공개 API만 쓴다(Modulith named interface `provided`, INV-18).
 * 값은 문자열·숫자·시각만 담은 DTO다(paymentfact domain 타입을 노출하지 않는다).
 */
interface PaymentFactAcquisitionSource {
    /**
     * 매입사 [acquirerCode]·환경 [environment]의 승인 투영 중 `approvedAt ≤ approvedUpTo`인 것의 키((approvedAt, transactionId) 순). 잠금 없음 — 후보를 고르는 용도이고,
     * 판정은 [lockAndRead]의 결과로 한다.
     */
    fun authorizationKeys(acquirerCode: String, environment: String, approvedUpTo: Instant): List<AcquisitionAuthorizationKey>

    /**
     * 결제별 직렬화 행을 payment_id 순으로 `FOR UPDATE` 잠근 뒤(취소 Inbox TX와 같은 행에서 줄을 선다 — 상계 경계 D-ACQ-012) 그 Payment들의 승인·취소 투영과
     * 아직 해제되지 않은 보류의 거래 ID를 읽는다. 잠금 뒤 읽기는 READ COMMITTED의 최신 커밋 값이다(호출자 TX가 READ COMMITTED여야 한다).
     */
    fun lockAndRead(paymentIds: Collection<String>): List<AcquisitionSourcePayment>

    /**
     * (ACQUIRING-001 2-F — D-ACQ-009) 원승인이 매입사 [acquirerCode]·환경 [environment]이고 인 취소 투영의 키((cancelledAt, transactionId) 순).
     * 잠금 없음 — 전송 후 취소 후보를 고르는 용도이고, 판정은 [lockAndRead]의 결과로 한다. 원승인 투영이 없는 취소(보류)는 나오지 않는다(원승인 매입사를 알 수 없다).
     */
    fun cancellationKeys(acquirerCode: String, environment: String, cancelledUpTo: Instant): List<AcquisitionCancellationKey>

    /** (ACQ-ADMIN-001 재매입) 승인 거래 ID → Payment ID(승인 투영에 있는 것만). 잠금 없음 — 잠금은 [lockAndRead]. */
    fun paymentIdsOf(authorizationTransactionIds: Collection<String>): Map<String, String>

    /**
     * (ACQ-ADMIN-001 재매입 미리 보기 — 비권위) [lockAndRead]와 같은 값을 **잠그지 않고** 읽는다. 판정(실행)에는 쓰지 않는다 — 실행 TX는 [lockAndRead]로 다시 읽는다.
     */
    fun readWithoutLock(paymentIds: Collection<String>): List<AcquisitionSourcePayment>
}

data class AcquisitionAuthorizationKey(val paymentId: String, val transactionId: String)

data class AcquisitionCancellationKey(val paymentId: String, val transactionId: String, val originalTransactionId: String)

/** 한 Payment의 매입 판단 입력. [unreleasedHoldTransactionIds] = 보류가 있고 RELEASED 해제 기록이 없는 거래 ID(승인·취소 모두). */
data class AcquisitionSourcePayment(
    val paymentId: String,
    val authorizations: List<AcquisitionSourceAuthorization>,
    val cancellations: List<AcquisitionSourceCancellation>,
    val unreleasedHoldTransactionIds: Set<String>,
)

/** 승인 투영(원승인 고정값). [merchantAccountId] = PayCore MID(D-ACQ-008 — Demo 매입 레코드의 가맹점 식별자). */
data class AcquisitionSourceAuthorization(
    val transactionId: String,
    val paymentId: String,
    val environment: String,
    val merchantAccountId: String,
    val amountMinor: Long,
    val currency: String,
    val planKind: String,
    val installmentMonths: Int?,
    val cardType: String,
    val issuerCode: String,
    val acquirerCode: String,
    val approvalNumber: String,
    val providerTransactionId: String,
    val institutionTransactionDate: LocalDate,
    val approvedAt: Instant,
)

/** 취소 투영. [merchantAccountId]·[institutionTransactionDate](2-F)는 전송 후 취소 CANCEL 레코드 값이다. */
data class AcquisitionSourceCancellation(
    val transactionId: String,
    val originalTransactionId: String,
    val amountMinor: Long,
    val currency: String,
    val providerTransactionId: String,
    val originalProviderTransactionId: String,
    val cancelledAt: Instant,
    val merchantAccountId: String,
    val institutionTransactionDate: LocalDate,
)
