package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.AuthorizationAdmission
import com.paycore.payment.payment.domain.AuthorizationAttempt
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.AuthorizationTransaction
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.LimitUsageKey
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ObservationKey
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentTransactionId
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.payment.payment.domain.UnknownCause

/**
 * `PaymentAuthorization` ↔ 행 변환(AUTH-001 명세 §7.5). 복원은 `PaymentAuthorization.reconstitute`(존재할 수 없는 조합 거부)만 쓴다 — 시험 편의 생성 경로가 아니다.
 * 관찰 행의 variant 0은 첫 관찰, n ≥ 1은 같은 키·다른 내용의 상충 증거([PaymentAuthorization.keyConflictingObservations])다.
 */
internal object PaymentAuthorizationPersistenceMapper {

    fun newRow(a: PaymentAuthorization): JpaPaymentAuthorizationEntity {
        val (planKind, months) = PaymentPlanColumns.of(a.plan)
        val order = PaymentPersistenceMapper.orderKeyColumns(a.orderKey)
        return JpaPaymentAuthorizationEntity.newRow(
            authorizationId = a.id.value, paymentId = a.paymentId.value, environment = order.environment, merchantAccountId = order.merchantAccountId,
            merchantOrderId = order.merchantOrderId, merchantSiteId = a.intent.merchantSiteId.value, amountMinor = a.money.amountMinor, currency = a.money.currency.value,
            planKind = planKind, installmentMonths = months, authenticatedCardType = a.authenticatedCardType.name, authenticationSessionId = a.authenticationSessionId.value,
            entitlementVersion = a.admission.entitlementVersion, policyAssignmentVersion = a.admission.policyAssignmentVersion, routeProfile = a.admission.routeProfile,
            protocolVersion = a.admission.protocolVersion, evaluatedAt = a.admission.evaluatedAt, planPolicyVersion = a.planPolicyVersion,
            limitPolicyVersion = a.limitPolicyVersion, limitBusinessDate = a.limitKey.businessDate, status = a.status.name, failureEvidence = a.failureEvidence?.name,
            openedAt = a.openedAt,
        )
    }

    fun newAttemptRow(authorizationId: AuthorizationId, attempt: AuthorizationAttempt, seq: Int): JpaAuthorizationAttemptEntity {
        val r = attempt.providerRequest
        return JpaAuthorizationAttemptEntity.newRow(
            attemptId = attempt.id.value, authorizationId = authorizationId.value, institution = r.institution, environment = r.environment.name,
            institutionAccount = r.institutionAccount, operation = r.operation, requestId = r.requestId, state = attempt.state.name,
            abandonCause = attempt.abandonCause?.name, unknownCause = attempt.unknownCause?.name, preparedAt = attempt.preparedAt, seq = seq,
            routeProfileVersion = attempt.routeProfileVersion,
        )
    }

    fun newObservationRow(authorizationId: AuthorizationId, o: InstitutionObservation, variant: Int): JpaInstitutionObservationEntity {
        val t = o.approved
        val plan = t?.let { PaymentPlanColumns.of(it.plan) }
        return JpaInstitutionObservationEntity.newRow(
            authorizationId = authorizationId.value, attemptId = o.attemptId.value, source = o.source.name, sourceResultId = o.sourceResultId, variant = variant,
            outcome = o.outcome.name, approvedAmountMinor = t?.money?.amountMinor, approvedCurrency = t?.money?.currency?.value, approvedPlanKind = plan?.first,
            approvedInstallmentMonths = plan?.second, approvedCardType = t?.cardType?.name, approvalNumber = t?.approvalNumber,
            providerTransactionId = t?.providerTransactionId, institutionTransactionDate = t?.institutionTransactionDate,
            institutionBusinessDate = t?.institutionBusinessDate, evidenceHash = o.evidenceHash,
            observedAt = o.observedAt,
        )
    }

    /** [recordedBy]: 결과 반영(AUTHORIZED 승인)이면 RESOLUTION, FAILED 승인에 RECON이 기록한 늦은 승인 거래(D-CANCEL-011)면 RECONCILIATION. */
    fun newTransactionRow(t: AuthorizationTransaction, recordedBy: String): JpaPaymentTransactionEntity {
        val (planKind, months) = PaymentPlanColumns.of(t.terms.plan)
        return JpaPaymentTransactionEntity.newAuthorizationRow(
            transactionId = t.id.value, paymentId = t.paymentId.value, authorizationId = t.authorizationId.value, amountMinor = t.terms.money.amountMinor,
            currency = t.terms.money.currency.value, planKind = planKind, installmentMonths = months, cardType = t.terms.cardType.name,
            approvalNumber = t.terms.approvalNumber, providerTransactionId = t.terms.providerTransactionId, institutionTransactionDate = t.terms.institutionTransactionDate,
            institutionBusinessDate = t.terms.institutionBusinessDate,
            sourceAttemptId = t.sourceObservation.attemptId.value, sourceKind = t.sourceObservation.source.name, sourceResultId = t.sourceObservation.sourceResultId,
            recordedBy = recordedBy, confirmedAt = t.confirmedAt,
        )
    }

    fun recordedBy(a: PaymentAuthorization): String = if (a.status == AuthorizationStatus.FAILED) RECORDED_BY_RECONCILIATION else RECORDED_BY_RESOLUTION

    fun toAttempt(row: JpaAuthorizationAttemptEntity): AuthorizationAttempt =
        AuthorizationAttempt(
            AttemptId(row.attemptId),
            ProviderRequestRef(row.institution, Environment.valueOf(row.environment), row.institutionAccount, row.operation, row.requestId),
            row.preparedAt,
            AttemptState.valueOf(row.state),
            row.abandonCause?.let(AbandonCause::valueOf),
            row.unknownCause?.let(UnknownCause::valueOf),
            row.routeProfileVersion,
        )

    fun toObservation(row: JpaInstitutionObservationEntity): InstitutionObservation =
        InstitutionObservation(
            attemptId = AttemptId(row.attemptId),
            source = ObservationSource.valueOf(row.source),
            sourceResultId = row.sourceResultId,
            outcome = InstitutionOutcome.valueOf(row.outcome),
            approved = row.approvedAmountMinor?.let { amount ->
                ApprovedTerms(
                    Money(amount, CurrencyCode(checkNotNull(row.approvedCurrency))),
                    PaymentPlanColumns.toPlan(checkNotNull(row.approvedPlanKind), row.approvedInstallmentMonths),
                    CardType.valueOf(checkNotNull(row.approvedCardType)),
                    checkNotNull(row.approvalNumber),
                    checkNotNull(row.providerTransactionId),
                    checkNotNull(row.institutionTransactionDate),
                    row.institutionBusinessDate,
                )
            },
            evidenceHash = row.evidenceHash,
            observedAt = row.observedAt,
        )

    fun toTransaction(row: JpaPaymentTransactionEntity): AuthorizationTransaction {
        check(row.type == "AUTHORIZATION") { "transaction ${row.transactionId} is not an authorization transaction" }
        return AuthorizationTransaction(
            id = PaymentTransactionId(row.transactionId),
            authorizationId = AuthorizationId(row.authorizationId),
            paymentId = PaymentId(row.paymentId),
            terms = ApprovedTerms(
                Money(row.amountMinor, CurrencyCode(row.currency)), PaymentPlanColumns.toPlan(checkNotNull(row.planKind), row.installmentMonths),
                CardType.valueOf(checkNotNull(row.cardType)), checkNotNull(row.approvalNumber), row.providerTransactionId, row.institutionTransactionDate, row.institutionBusinessDate,
            ),
            sourceObservation = ObservationKey(AttemptId(row.sourceAttemptId), ObservationSource.valueOf(row.sourceKind), row.sourceResultId),
            confirmedAt = row.confirmedAt,
        )
    }

    fun toAuthorization(
        row: JpaPaymentAuthorizationEntity,
        attempts: List<JpaAuthorizationAttemptEntity>,
        observations: List<JpaInstitutionObservationEntity>,
        transaction: JpaPaymentTransactionEntity?,
    ): PaymentAuthorization {
        val environment = Environment.valueOf(row.environment)
        val orderKey = PaymentPersistenceMapper.orderKeyOf(row.environment, row.merchantAccountId, row.merchantOrderId)
        val currency = CurrencyCode(row.currency)
        return PaymentAuthorization.reconstitute(
            id = AuthorizationId(row.authorizationId),
            paymentId = PaymentId(row.paymentId),
            orderKey = orderKey,
            intent = OrderIntent(MerchantSiteId(row.merchantSiteId), Money(row.amountMinor, currency)),
            plan = PaymentPlanColumns.toPlan(row.planKind, row.installmentMonths),
            authenticatedCardType = CardType.valueOf(row.authenticatedCardType),
            authenticationSessionId = AuthenticationSessionId(row.authenticationSessionId),
            admission = AuthorizationAdmission(row.entitlementVersion, row.policyAssignmentVersion, row.routeProfile, row.protocolVersion, row.evaluatedAt),
            planPolicyVersion = row.planPolicyVersion,
            limitPolicyVersion = row.limitPolicyVersion,
            limitKey = LimitUsageKey(environment, orderKey.merchantAccountId, currency, row.limitBusinessDate),
            openedAt = row.openedAt,
            status = AuthorizationStatus.valueOf(row.status),
            attempts = attempts.sortedBy { it.seq }.map(::toAttempt),
            observations = observations.filter { it.variant == 0 }.map(::toObservation),
            // 같은 키의 상충 증거는 variant 순서가 domain 기록 순서다(검토 #1 H-1 — 복원 뒤 ObservationRef가 저장 때와 같아야 한다).
            keyConflictingObservations = observations.filter { it.variant > 0 }.sortedBy { it.variant }.map(::toObservation),
            transaction = transaction?.let(::toTransaction),
            failureEvidence = row.failureEvidence?.let(TerminationEvidence::valueOf),
        )
    }

    /** domain의 관찰 목록을 저장 순서의 (관찰, variant) 행으로 펼친다: 첫 관찰은 0, 같은 키의 상충 증거는 그 키 안에서 1, 2, … */
    fun observationRows(a: PaymentAuthorization): List<Pair<InstitutionObservation, Int>> = a.observationRefs.map { (o, ref) -> o to ref.variant }

    const val RECORDED_BY_RESOLUTION = "RESOLUTION"
    const val RECORDED_BY_RECONCILIATION = "RECONCILIATION"
}
