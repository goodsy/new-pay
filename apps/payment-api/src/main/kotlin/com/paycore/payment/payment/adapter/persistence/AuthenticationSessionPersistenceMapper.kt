package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.AuthenticationSessionAttributes
import com.paycore.payment.payment.domain.AuthenticationBinding
import com.paycore.payment.payment.domain.AuthenticationCorrelation
import com.paycore.payment.payment.domain.AuthenticationExpiryCause
import com.paycore.payment.payment.domain.AuthenticationFailure
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.AuthenticationStatus
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.VerifiedAuthentication

/**
 * AuthenticationSession ↔ authentication_session 행 Mapper(AUTHN-001 §16 Entity/Domain 분리). 복원은 domain `reconstitute`(존재할 수 없는 조합 거부)로만 한다.
 * 갱신은 바뀌는 컬럼(상태·기관 참조·영수증·증거·사유·원인·귀속)만 옮기고, 생성 시 불변 값이 같은 세션인지 먼저 확인한다.
 */
internal object AuthenticationSessionPersistenceMapper {

    fun newRow(session: AuthenticationSession, attributes: AuthenticationSessionAttributes): JpaAuthenticationSessionEntity {
        require(session.status == AuthenticationStatus.CREATED) { "a new authentication session row must be CREATED: ${session.status}" }
        val (planKind, months) = planColumns(session.binding.plan)
        return JpaAuthenticationSessionEntity.newRow(
            authenticationSessionId = session.id.value,
            paymentId = session.binding.paymentId.value,
            checkoutSessionId = attributes.checkoutSessionId.value,
            environment = session.binding.orderKey.environment.name,
            merchantAccountId = session.binding.orderKey.merchantAccountId.value,
            merchantOrderId = session.binding.orderKey.merchantOrderId.value,
            amountMinor = session.binding.money.amountMinor,
            currency = session.binding.money.currency.value,
            planKind = planKind,
            installmentMonths = months,
            cardCompany = session.binding.cardCompany,
            stateDigest = session.correlation.stateDigest.value,
            profileId = attributes.profileId,
            planPolicyVersion = attributes.planPolicyVersion,
            policyVersion = attributes.policyVersion,
            createdAt = session.createdAt,
            expiresAt = session.expiresAt,
        )
    }

    fun toSession(row: JpaAuthenticationSessionEntity): AuthenticationSession {
        val binding = AuthenticationBinding(
            paymentId = PaymentId(row.paymentId),
            orderKey = PaymentPersistenceMapper.orderKeyOf(row.environment, row.merchantAccountId, row.merchantOrderId),
            money = Money(row.amountMinor, CurrencyCode(row.currency)),
            plan = planOf(row.planKind, row.installmentMonths),
            cardCompany = row.cardCompany,
        )
        val evidence = row.evidenceRef?.let {
            VerifiedAuthentication(
                evidenceRef = it,
                binding = binding,
                cardType = CardType.valueOf(checkNotNull(row.cardType)),
                issuerCode = checkNotNull(row.issuerCode),
                acquirerCode = checkNotNull(row.acquirerCode),
                providerCode = checkNotNull(row.providerCode),
                providerValidUntil = checkNotNull(row.providerValidUntil),
            )
        }
        return AuthenticationSession.reconstitute(
            id = AuthenticationSessionId(row.authenticationSessionId),
            binding = binding,
            correlation = AuthenticationCorrelation(SecretDigest.parse(row.stateDigest)),
            expiresAt = row.expiresAt,
            createdAt = row.createdAt,
            status = AuthenticationStatus.valueOf(row.status),
            providerTransactionRef = row.providerTransactionRef,
            verified = evidence,
            verifiedAt = row.verifiedAt,
            resultRefDigest = row.resultRefDigest?.let(SecretDigest::parse),
            authenticationRef = row.authenticationRef?.let(::AuthenticationRef),
            failure = row.failureReason?.let(AuthenticationFailure::valueOf),
            expiryCause = row.expiryCause?.let(AuthenticationExpiryCause::valueOf),
            boundAuthorizationId = row.boundAuthorizationId?.let(::AuthorizationId),
        )
    }

    /** 바뀌는 컬럼을 옮긴다. 같은 세션(식별자·상관 값·만료 시각)인지 먼저 확인한다 — 다르면 Mapper 사용 결함. */
    fun apply(session: AuthenticationSession, row: JpaAuthenticationSessionEntity) {
        check(
            row.authenticationSessionId == session.id.value && row.stateDigest == session.correlation.stateDigest.value &&
                row.expiresAt == session.expiresAt && row.paymentId == session.binding.paymentId.value,
        ) { "authentication session row ${row.authenticationSessionId} does not match the domain session" }
        val evidence = session.verified
        row.status = session.status.name
        row.providerTransactionRef = session.providerTransactionRef
        row.resultRefDigest = session.resultRefDigest?.value
        row.failureReason = session.failure?.name
        row.expiryCause = session.expiryCause?.name
        row.evidenceRef = evidence?.evidenceRef
        row.cardType = evidence?.cardType?.name
        row.issuerCode = evidence?.issuerCode
        row.acquirerCode = evidence?.acquirerCode
        row.providerCode = evidence?.providerCode
        row.providerValidUntil = evidence?.providerValidUntil
        row.verifiedAt = session.verifiedAt
        row.authenticationRef = session.authenticationRef?.value
        row.boundAuthorizationId = session.boundAuthorizationId?.value
    }

    private fun planColumns(plan: PaymentPlan): Pair<String, Int?> = when (plan) {
        PaymentPlan.LumpSum -> "LUMP_SUM" to null
        is PaymentPlan.Installment -> "INSTALLMENT" to plan.months
    }

    private fun planOf(kind: String, months: Int?): PaymentPlan = when (kind) {
        "LUMP_SUM" -> PaymentPlan.LumpSum.also { check(months == null) { "lump sum has no months" } }
        "INSTALLMENT" -> PaymentPlan.Installment(checkNotNull(months) { "installment needs months" })
        else -> error("unknown plan kind")
    }
}
