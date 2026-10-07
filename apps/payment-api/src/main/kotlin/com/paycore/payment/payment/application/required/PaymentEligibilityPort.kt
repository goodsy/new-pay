package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.application.provided.EligibilityUnavailableReason
import com.paycore.payment.payment.application.provided.NotEligibleReason
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.CurrencyUnit
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import java.time.Instant

/**
 * 결제 생성 eligibility(CP-CONTRACT-001 §13, D-CPC-006). MID 소유·Site 관계(같은 Merchant·유효 Assignment)·계약/개통 준비·허용 통화·
 * 결제수단·Snapshot freshness를 평가한다.
 *
 * 구현 규칙(구현은 MASTER-SNAPSHOT-001 → CP-API-001, 그 전까지 운영 구현 bean 없음):
 * - **PAYMENT DB의 로컬 Snapshot 투영만 읽는다.** MASTER DB 직접 조회·원격(HTTP/브로커) 호출 금지 — 호출자가 멱등 기록 잠금을 쥔 채
 *   **같은 PAYMENT 로컬 TX 안에서** 호출한다. 그래서 이 Port 안의 쓰기·외부 호출·긴 대기는 금지다.
 * - 판정 불가(Snapshot 없음·만료·지원하지 않는 버전)는 예외가 아니라 [EligibilityResult.Unavailable]로 돌려준다(fail-closed).
 * - v1은 건당·일 한도를 평가하지 않는다(한도 reason 없음, 계약 §4·D-CPC-008).
 * - 생성 시 평가는 승인 시 재검사(AUTH)를 대신하지 않는다.
 */
interface PaymentEligibilityPort {
    /**
     * @param authenticatedMid 인증 Credential의 MID(body MID와 같음을 호출자가 확인). [merchantSiteId]가 이 MID와 같은 Merchant의 유효
     *   Assignment가 아니면 [EligibilityResult.NotFoundOrForbidden].
     * @param evaluatedAt 서버 clock(Snapshot freshness 기준). 가맹점 시각이 아니다.
     */
    fun evaluate(
        environment: Environment,
        authenticatedMid: MerchantAccountId,
        merchantSiteId: MerchantSiteId,
        currency: CurrencyCode,
        paymentMethod: PaymentMethod,
        evaluatedAt: Instant,
    ): EligibilityResult
}

/** [PaymentEligibilityPort.evaluate] 결과(계약 §13). */
sealed interface EligibilityResult {
    /**
     * 생성 가능. [snapshotVersion]은 Payment에 보존된다(Auditability). [currencyUnit]은 요청 통화의 기준정보 minor unit(코드 허용 목록
     * 대신 기준정보가 통화를 활성화한다, §4). [validUntil]은 이 판정을 쓸 수 있는 Snapshot 유효 시각.
     */
    data class Eligible(val snapshotVersion: EligibilitySnapshotVersion, val validUntil: Instant, val currencyUnit: CurrencyUnit) : EligibilityResult

    /** Site 없음·다른 Merchant/MID의 Site·Assignment 무효(구분하지 않음). */
    data object NotFoundOrForbidden : EligibilityResult

    data class NotEligible(val reason: NotEligibleReason) : EligibilityResult

    data class Unavailable(val reason: EligibilityUnavailableReason) : EligibilityResult
}
