package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentMethod

/**
 * 형식 검증을 통과한 CreatePayment 요청(web adapter가 엄격 파싱한 값, CP-API-001 명세 §4). [merchantAccountId]는 본문 값이며 인증 MID와
 * 같음을 web이 403으로 이미 확인했다(이 Use Case도 다시 확인한다 — 다르면 결함). environment·MID 범위는 [authentication]에서만 온다.
 */
data class CreatePaymentRequest(
    val authentication: MerchantAuthentication,
    val idempotencyKey: IdempotencyKey,
    val merchantAccountId: MerchantAccountId,
    val merchantSiteId: MerchantSiteId,
    val merchantOrderId: MerchantOrderId,
    val money: Money,
    val paymentMethod: PaymentMethod,
    val orderName: String?,
    val correlationId: String,
)

/**
 * CreatePayment 진입 facade(CP-API-001 명세 §3.1). HTTP와 무관한 준비 — 서버 시계, 서버 생성 식별자, 인증 주체(행위자) — 를 붙여
 * [CreatePaymentUseCase](로컬 TX 소유)를 부른다. 서버 생성 식별자가 기존 값과 충돌하면(rollback 확정) 새 식별자로 한 번만 다시 실행하고,
 * 그래도 충돌하면 [CreatePaymentFailure.TemporarilyUnavailable]이다(사용자 payload 충돌로 보이지 않게, 계약 §9 마지막 행).
 * 이 facade는 TX를 열지 않는다.
 */
interface SubmitCreatePaymentUseCase {
    fun submit(request: CreatePaymentRequest): CreatePaymentResult
}
