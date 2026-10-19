package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.AUTHORIZATION_ATTEMPT
import com.paycore.payment.jooq.generated.Tables.PAYMENT_AUTHORIZATION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_TRANSACTION
import com.paycore.payment.payment.application.required.AuthorizationAttemptViewRow
import com.paycore.payment.payment.application.required.AuthorizationViewReader
import com.paycore.payment.payment.application.required.AuthorizationViewRow
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.TerminationEvidence
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 승인 조회(CQRS 조회 측, jOOQ — AUTH-001 §7.6): payment_authorization + (있으면) 승인 거래 한 줄. 쓰기 없음(queryAdapterDoesNotWriteWithJooq), 읽기 전용 TX 하나,
 * **비권위** — confirm 응답·재생 표시와 결과 반영 전 주문 키 찾기에만 쓰고, 상태 변경은 JPA 잠금 재판정이 한다. 시각은 [UtcWallClock], 날짜는 문자열로 읽는다(시간대 해석 없음).
 */
@Component
class JooqAuthorizationViewReader(private val dsl: DSLContext) : AuthorizationViewReader {

    @Transactional(readOnly = true)
    override fun find(authorizationId: AuthorizationId): AuthorizationViewRow? = try {
        query(authorizationId)
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    /** 회복 Worker의 Attempt 정보(authorization_attempt + Authorization의 Route). 비권위 — 상태 판정은 잠금 재판정이 한다. */
    @Transactional(readOnly = true)
    override fun findAttempt(attemptId: AttemptId): AuthorizationAttemptViewRow? = try {
        val t = AUTHORIZATION_ATTEMPT
        val a = PAYMENT_AUTHORIZATION
        val preparedAt = UtcWallClock.iso(t.PREPARED_AT)
        dsl.select(
            t.ATTEMPT_ID, t.AUTHORIZATION_ID, t.STATE, preparedAt, t.INSTITUTION, t.ENVIRONMENT, t.INSTITUTION_ACCOUNT, t.OPERATION, t.REQUEST_ID, a.ROUTE_PROFILE,
            t.ROUTE_PROFILE_VERSION,
        )
            .from(t).join(a).on(a.AUTHORIZATION_ID.eq(t.AUTHORIZATION_ID))
            .where(t.ATTEMPT_ID.eq(attemptId.value))
            .fetchOne { r ->
                AuthorizationAttemptViewRow(
                    attemptId = AttemptId(r.get(t.ATTEMPT_ID)),
                    authorizationId = AuthorizationId(r.get(t.AUTHORIZATION_ID)),
                    state = AttemptState.valueOf(r.get(t.STATE)),
                    preparedAt = checkNotNull(UtcWallClock.parse(r.get(preparedAt))),
                    providerRequest = ProviderRequestRef(
                        r.get(t.INSTITUTION), Environment.valueOf(r.get(t.ENVIRONMENT)), r.get(t.INSTITUTION_ACCOUNT), r.get(t.OPERATION), r.get(t.REQUEST_ID),
                    ),
                    routeRef = r.get(a.ROUTE_PROFILE),
                    routeProfileVersion = r.get(t.ROUTE_PROFILE_VERSION),
                )
            }
    } catch (e: DataAccessException) {
        throw PersistenceFailure.Unavailable(e)
    }

    private fun query(authorizationId: AuthorizationId): AuthorizationViewRow? {
        val a = PAYMENT_AUTHORIZATION
        val t = PAYMENT_TRANSACTION
        val confirmedAt = UtcWallClock.iso(t.CONFIRMED_AT)
        val txDate = date(t.INSTITUTION_TRANSACTION_DATE)
        val businessDate = date(t.INSTITUTION_BUSINESS_DATE)
        return dsl.select(
            a.AUTHORIZATION_ID, a.PAYMENT_ID, a.ENVIRONMENT, a.MERCHANT_ACCOUNT_ID, a.MERCHANT_ORDER_ID, a.STATUS, a.FAILURE_EVIDENCE,
            t.TRANSACTION_ID, t.AMOUNT_MINOR, t.CURRENCY, t.PLAN_KIND, t.INSTALLMENT_MONTHS, t.CARD_TYPE, t.APPROVAL_NUMBER, t.PROVIDER_TRANSACTION_ID, txDate, businessDate,
            confirmedAt,
        )
            // 검토 #3 n-3: 승인 거래만(취소 등 다른 거래 종류가 추가돼도 한 줄).
            .from(a).leftJoin(t).on(t.AUTHORIZATION_ID.eq(a.AUTHORIZATION_ID).and(t.TYPE.eq("AUTHORIZATION")))
            .where(a.AUTHORIZATION_ID.eq(authorizationId.value))
            .fetchOne { r ->
                val terms = r.get(t.TRANSACTION_ID)?.let {
                    ApprovedTerms(
                        money = Money(r.get(t.AMOUNT_MINOR), CurrencyCode(r.get(t.CURRENCY))),
                        plan = if (r.get(t.PLAN_KIND) == "INSTALLMENT") PaymentPlan.Installment(r.get(t.INSTALLMENT_MONTHS)) else PaymentPlan.LumpSum,
                        cardType = CardType.valueOf(r.get(t.CARD_TYPE)),
                        approvalNumber = r.get(t.APPROVAL_NUMBER),
                        providerTransactionId = r.get(t.PROVIDER_TRANSACTION_ID),
                        institutionTransactionDate = LocalDate.parse(r.get(txDate)),
                        institutionBusinessDate = r.get(businessDate)?.let(LocalDate::parse),
                    )
                }
                AuthorizationViewRow(
                    authorizationId = AuthorizationId(r.get(a.AUTHORIZATION_ID)),
                    paymentId = PaymentId(r.get(a.PAYMENT_ID)),
                    orderKey = OrderKey(Environment.valueOf(r.get(a.ENVIRONMENT)), MerchantAccountId(r.get(a.MERCHANT_ACCOUNT_ID)), MerchantOrderId(r.get(a.MERCHANT_ORDER_ID))),
                    status = AuthorizationStatus.valueOf(r.get(a.STATUS)),
                    failureEvidence = r.get(a.FAILURE_EVIDENCE)?.let(TerminationEvidence::valueOf),
                    approved = terms,
                    approvedAt = UtcWallClock.parse(r.get(confirmedAt)),
                )
            }
    }

    private fun date(field: Field<*>): Field<String> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%d')", String::class.java, field)
}
