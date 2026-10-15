package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CreatePaymentCommand
import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentRequest
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.SubmitCreatePaymentUseCase
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentIdentifierGenerator
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * CreatePayment facade(CP-API-001 명세 §3.1·§3.5). TX를 열지 않는다 — [CreatePaymentUseCase]가 로컬 TX를 소유한다.
 *
 * - OrderKey의 environment·MID는 **인증 주체에서만** 온다(계약 §2). 본문 MID가 인증 MID와 다르면 web이 403으로 막았어야 하므로 여기서는 결함이다.
 * - 요청 시각은 서버 시계, 식별자는 서버 생성기에서 온다.
 * - 서버 생성 식별자 충돌(rollback 확정)은 새 식별자로 같은 요청을 한 번 더 실행한다(같은 멱등키·같은 요청 시각). 두 번째도 충돌이면
 *   일시 불가(TEMPORARILY_UNAVAILABLE, 로그 분류 IDENTIFIER_COLLISION)다 — 사용자 payload 충돌(409)로 보이지 않는다.
 */
@Service
class SubmitCreatePaymentService(
    private val createPayment: CreatePaymentUseCase,
    private val clock: PaymentClock,
    private val identifiers: PaymentIdentifierGenerator,
) : SubmitCreatePaymentUseCase {

    private val log = LoggerFactory.getLogger(SubmitCreatePaymentService::class.java)

    override fun submit(request: CreatePaymentRequest): CreatePaymentResult {
        val auth = request.authentication
        require(request.merchantAccountId == auth.merchantAccountId) { "body merchantAccountId must equal the authenticated MID" }
        val command = CreatePaymentCommand(
            orderKey = OrderKey(auth.environment, auth.merchantAccountId, request.merchantOrderId),
            orderIntent = OrderIntent(request.merchantSiteId, request.money),
            paymentMethod = request.paymentMethod,
            orderName = request.orderName,
            idempotencyKey = request.idempotencyKey,
            requestedAt = clock.now(),
            correlationId = request.correlationId,
            newIdentifiers = identifiers.newIdentifiers(),
            actor = auth.credentialKeyId,
        )
        return try {
            createPayment.create(command)
        } catch (first: CreatePaymentFailure.IdentifierCollision) {
            log.warn("operation=CreatePayment correlationId={} failure=IDENTIFIER_COLLISION identifier={} action=retry_with_new_identifiers",
                request.correlationId, first.identifier)
            try {
                createPayment.create(command.copy(newIdentifiers = identifiers.newIdentifiers()))
            } catch (second: CreatePaymentFailure.IdentifierCollision) {
                throw CreatePaymentFailure.TemporarilyUnavailable(second)
            }
        }
    }
}
