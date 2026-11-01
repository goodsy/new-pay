package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.EntitlementCommandFixture.reason
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.ApproveCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.AssignPolicyCommand
import com.paycore.master.merchant.application.provided.LimitTargetInput
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.RegisterCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.RegisterPolicyVersionCommand
import com.paycore.master.merchant.application.provided.RegisterTransactionLimitPolicyCommand
import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.RouteRef
import com.paycore.master.merchant.domain.TransactionLimitPolicyId
import java.time.Instant
import java.util.UUID

/**
 * 승인 시작 조건 명령의 Named Fixture(src/test 전용, 공식 use case로만 만든다). 한도·정책·Route 값은 **Demo**(운영값 아님 — 명세 §10).
 * 시계 기본값 [OnboardingFixture.t0] = 2026-10-09T02:00Z(11:00 KST) → 다음 Seoul 자정은 [midnight1].
 */
object AuthorizationTermsCommandFixture {
    val midnight1: Instant = Instant.parse("2026-10-09T15:00:00.000Z")
    val midnight2: Instant = Instant.parse("2026-10-10T15:00:00.000Z")
    val demoPerTransaction = LimitValue.Limited(1_000_000)
    val demoDaily = LimitValue.Limited(5_000_000)
    val acquirer = AcquirerCode("SIMCARD01")
    val routeRef = RouteRef("SIMVAN01")
    val institutionRef = InstitutionMerchantRef("SIMMERCH0001")

    /** 코드 충돌 없이 시험마다 새 정책 코드(같은 코드 번호 증가 시험은 직접 코드를 준다). */
    fun code(prefix: String) = PolicyCode(prefix + "-" + UUID.randomUUID().toString().substring(0, 8).uppercase())

    fun registerVersion(terms: MerchantAuthorizationTermsUseCase, kind: PolicyKind, code: PolicyCode = code(kind.name.take(3))): PolicyVersionId =
        PolicyVersionId(succeeded(terms.registerPolicyVersion(RegisterPolicyVersionCommand(request(), kind, code, PolicyContentStatus.DEMO, null, reason))))

    fun assign(terms: MerchantAuthorizationTermsUseCase, e: EntitlementCommandFixture.Entitled, version: PolicyVersionId, from: Instant = OnboardingFixture.t0): PolicyAssignmentId =
        PolicyAssignmentId(
            succeeded(
                terms.assignPolicy(AssignPolicyCommand(request(), e.mid, e.ready.agreementId, CurrencyCode("KRW"), version, EffectivePeriod(from), reason)),
            ),
        )

    fun csaDefault(e: EntitlementCommandFixture.Entitled, period: EffectivePeriod = EffectivePeriod(midnight1)) = RegisterTransactionLimitPolicyCommand(
        request(), LimitTargetInput.CsaDefault(e.ready.contractId, e.ready.agreementId), CurrencyCode("KRW"), demoPerTransaction, demoDaily, period, reason,
    )

    fun registerCsaDefault(terms: MerchantAuthorizationTermsUseCase, e: EntitlementCommandFixture.Entitled): TransactionLimitPolicyId =
        TransactionLimitPolicyId(succeeded(terms.registerTransactionLimitPolicy(csaDefault(e))))

    fun route(e: EntitlementCommandFixture.Entitled, code: AcquirerCode = acquirer) =
        RegisterCardRouteActivationCommand(request(), e.mid, PaymentMethod.CARD, code, routeRef, institutionRef)

    fun activeRoute(terms: MerchantAuthorizationTermsUseCase, e: EntitlementCommandFixture.Entitled): CardRouteActivationId {
        val id = CardRouteActivationId(succeeded(terms.registerCardRouteActivation(route(e))))
        succeeded(terms.approveCardRouteActivation(ApproveCardRouteActivationCommand(request(), id, EntitlementCommandFixture.evidence)))
        return id
    }

    /** 승인 시작 조건까지 갖춘 Merchant: entitled + 수수료·정산 Assignment + CSA 기본 한도(다음 Seoul 자정부터) + ACTIVE 카드사 개통. */
    data class Ready(val entitled: EntitlementCommandFixture.Entitled, val fee: PolicyAssignmentId, val settlement: PolicyAssignmentId, val limit: TransactionLimitPolicyId, val route: CardRouteActivationId)

    fun ready(terms: MerchantAuthorizationTermsUseCase, e: EntitlementCommandFixture.Entitled): Ready = Ready(
        e,
        assign(terms, e, registerVersion(terms, PolicyKind.FEE_SCHEDULE)),
        assign(terms, e, registerVersion(terms, PolicyKind.SETTLEMENT_POLICY)),
        registerCsaDefault(terms, e),
        activeRoute(terms, e),
    )
}
