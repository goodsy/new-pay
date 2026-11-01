package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.AuthorizationTermsQuery
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsRefusal
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsRequest
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsResult
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsUnavailable
import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.LimitBound
import com.paycore.payment.entitlement.application.provided.LimitTerms
import com.paycore.payment.entitlement.application.provided.PolicyTerms
import com.paycore.payment.entitlement.application.provided.RouteTerms
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmissionUnavailableReason
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.SQLException
import java.time.Instant

/**
 * 승인 시작 허용 판정 adapter의 변환(MASTER-SNAPSHOT-002 명세 §6.3) — entitlement 공개 결과 → payment Port 결과. 판정 자체는 entitlement domain 시험이
 * 본다. 값은 Demo(식별자는 시험 값).
 */
class EntitlementAuthorizationAdmissionAdapterSpec : FunSpec({

    val at = Instant.parse("2026-10-09T02:00:05.000Z")
    val mid = MerchantAccountId("mid_TEST0000000000000000000001")
    val fee = PolicyTerms("pas_FEE", "pv_FEE", "FEE-DEMO", 1, "DEMO")
    val settlement = PolicyTerms("pas_STL", "pv_STL", "STL-DEMO", 1, "UNDEFINED")
    val route = RouteTerms("cra_1", "SIMCARD01", "SIMVAN01", "SIMMERCH0001")

    fun admitted(limit: LimitTerms) = AuthorizationTermsResult.Admitted("es1:mer_X:4", route, fee, settlement, limit, at.plusSeconds(30), at)

    class FixedQuery(private val answer: () -> AuthorizationTermsResult) : AuthorizationTermsQuery {
        var asked: AuthorizationTermsRequest? = null

        override fun evaluate(request: AuthorizationTermsRequest, evaluatedAt: Instant): AuthorizationTermsResult = answer().also { asked = request }
    }

    fun evaluate(query: AuthorizationTermsQuery, currency: String = "KRW") =
        EntitlementAuthorizationAdmissionAdapter(query).evaluate(mid, MerchantSiteId("site_1"), CurrencyCode(currency), PaymentMethod.CARD, "SIMCARD01", at)

    test("Admitted carries the pa1 assignment string, both content statuses, the route and a limit policy scoped to MID + currency") {
        val query = FixedQuery { admitted(LimitTerms.Resolved("tlp_1", "MID_OVERRIDE", "KRW", LimitBound.Limited(300_000), LimitBound.Unlimited)) }
        val result = evaluate(query).shouldBeInstanceOf<AdmissionResult.Admitted>()
        listOf(result.policyAssignmentVersion, result.policyContentStatus, result.route.routeRef, result.validUntil) shouldBe
            listOf("pa1:fee=pas_FEE/pv_FEE;stl=pas_STL/pv_STL", setOf("DEMO", "UNDEFINED"), "SIMVAN01", at.plusSeconds(30))
        result.limitPolicy shouldBeEqual LimitPolicyResolution.Resolved(
            TransactionLimitPolicy("tlp_1", mid, CurrencyCode("KRW"), LimitAmount.Limited(300_000), LimitAmount.Unlimited),
        )
        query.asked shouldBe AuthorizationTermsRequest(mid.value, "site_1", "CARD", "KRW", "SIMCARD01")
        result.toString().contains("SIMMERCH0001") shouldBe false
    }

    test("Missing and Ambiguous limits pass through, and a resolved policy of another currency is Missing (defence in depth)") {
        evaluate(FixedQuery { admitted(LimitTerms.Missing) }).shouldBeInstanceOf<AdmissionResult.Admitted>().limitPolicy shouldBe LimitPolicyResolution.Missing
        evaluate(FixedQuery { admitted(LimitTerms.Ambiguous) }).shouldBeInstanceOf<AdmissionResult.Admitted>().limitPolicy shouldBe LimitPolicyResolution.Ambiguous
        val usdPolicy = FixedQuery { admitted(LimitTerms.Resolved("tlp_2", "CSA_DEFAULT", "USD", LimitBound.Limited(1), LimitBound.Limited(1))) }
        evaluate(usdPolicy).shouldBeInstanceOf<AdmissionResult.Admitted>().limitPolicy shouldBe LimitPolicyResolution.Missing
    }

    test("refusals and unavailability map one to one") {
        AuthorizationTermsRefusal.entries.map { r -> evaluate(FixedQuery { AuthorizationTermsResult.Refused(r) }) } shouldBe
            AdmissionRefusalReason.entries.map { AdmissionResult.Refused(it) }
        AuthorizationTermsUnavailable.entries.map { r -> evaluate(FixedQuery { AuthorizationTermsResult.Unavailable(r) }) } shouldBe
            AdmissionUnavailableReason.entries.map { AdmissionResult.Unavailable(it) }
        evaluate(FixedQuery { AuthorizationTermsResult.NotFoundOrForbidden }) shouldBe AdmissionResult.NotFoundOrForbidden
    }

    test("store failures become payment persistence failures (deadlock, lock timeout, unavailable) — never a decision") {
        val cause = SQLException("x", "40001", 1213)
        shouldThrow<PersistenceFailure.Deadlock> { evaluate(FixedQuery { throw EntitlementEvaluationFailure.Deadlock(cause, 1213, "40001") }) }
        shouldThrow<PersistenceFailure.LockTimeout> { evaluate(FixedQuery { throw EntitlementEvaluationFailure.LockTimeout(cause, 1205, "HY000") }) }
        shouldThrow<PersistenceFailure.Unavailable> { evaluate(FixedQuery { throw EntitlementEvaluationFailure.Unavailable(cause) }) }
    }
})
