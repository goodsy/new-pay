package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.envelope
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.domain.CanonicalJson
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * DEMO-SEED-001 I-10(PAYMENT 쪽): harness가 앱 코드와 독립으로 만든 Demo seed 최신 Snapshot golden(`contracts/demo-seed-snapshot-v3.golden.json` — MASTER
 * `DemoSeedSnapshotGoldenSpec`이 같은 바이트를 확인)을 **실제 서명 Inbox**로 적용한 뒤 운영 승인 시작 판정 Port가 T0−1ms에는 `CONTRACT_NOT_EFFECTIVE`로 거절하고
 * T0에는 허용(한도 Resolved 1,000,000/5,000,000 — D-DEMO-003, Route SIMVAN01·매입사 SIMACQA, 정책 DEMO)하는지 본다. 값은 Demo — 운영값 아님.
 */
@EntitlementDbTest
class DemoSeedSnapshotAdmissionSpec(
    private val context: WebApplicationContext,
    private val port: AuthorizationAdmissionPort,
    private val transactionManager: PlatformTransactionManager,
    private val clock: MutableEntitlementClock,
) : FunSpec({

    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(ClassPathResource("contracts/demo-seed-snapshot-v3.golden.json").inputStream)
    fun g(name: String): String = golden.get(name).stringValue()
    val t0 = Instant.parse(g("effectiveFrom"))
    val tx = TransactionTemplate(transactionManager)

    afterAny { clock.reset() }

    fun admit(at: Instant): AdmissionResult = checkNotNull(
        tx.execute { port.evaluate(MerchantAccountId(g("mid")), MerchantSiteId(g("siteId")), CurrencyCode("KRW"), PaymentMethod.CARD, "SIMACQA", at) },
    )

    test("the golden snapshot is applied through the signed inbox; T0−1ms is CONTRACT_NOT_EFFECTIVE and T0 is admitted with the D-DEMO-003 limit") {
        // 전송 payload = golden 정규 내용 + contentHash + lease(시험 값 — T0 앞뒤를 덮는다). 봉투·서명은 운영 Inbox 규칙 그대로.
        @Suppress("UNCHECKED_CAST")
        val content = mapper.readValue(g("contentCanonical"), Map::class.java) as Map<String, Any?>
        val payload = CanonicalJson.write(
            content + mapOf(
                "contentHash" to g("contentHash"), "issuedAt" to Instant.parse("2026-10-09T14:59:00.000Z"), "leaseSeq" to 0L,
                "leaseUntil" to Instant.parse("2026-10-09T15:10:00.000Z"),
            ),
        )
        val body = envelope("5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b", golden.get("schemaVersion").intValue(), g("merchantId"), golden.get("revision").longValue(), payload)
        clock.set(Instant.parse("2026-10-09T14:59:30.000Z"))
        send(MockMvcBuilders.webAppContextSetup(context).build(), body).status shouldBe 200

        clock.set(t0.minusMillis(1))
        admit(t0.minusMillis(1)) shouldBe AdmissionResult.Refused(AdmissionRefusalReason.CONTRACT_NOT_EFFECTIVE)

        clock.set(t0)
        val admitted = admit(t0).shouldBeInstanceOf<AdmissionResult.Admitted>()
        admitted.entitlementVersion shouldBe "es1:${g("merchantId")}:12"
        admitted.route.routeRef shouldBe "SIMVAN01"
        admitted.route.acquirerCode shouldBe "SIMACQA"
        admitted.policyContentStatus shouldBe setOf("DEMO")
        admitted.limitPolicy shouldBe LimitPolicyResolution.Resolved(
            TransactionLimitPolicy(g("limitPolicyId"), MerchantAccountId(g("mid")), CurrencyCode("KRW"), LimitAmount.Limited(1_000_000), LimitAmount.Limited(5_000_000)),
        )
    }
})
