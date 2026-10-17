package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.envelope
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.EntitlementEnvelopes.signature
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.application.required.SiteWebHostPort
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.application.required.SiteWebHostUnavailable
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * schemaVersion 3 배포 순서(AUTHN-001 §9.2, MS2 §5.4 방식 — 검토 n-5): PAYMENT(v3 해석) 먼저, MASTER(v3 조립) 나중.
 * - 역순(PAYMENT가 해석하지 못하는 더 새 버전이 먼저 도착): head가 VERSION_UNSUPPORTED로 막혀 결제창 host·승인 판정 모두 fail-closed(가용성 사고, 금융 안전).
 *   PAYMENT가 이미 3을 지원하므로 "해석 못 하는 새 버전"은 지원 집합 밖 첫 값(4)으로 대표한다.
 * - 정순: v2 head에 MASTER 승격 발행(v3, revision + 1)이 APPLIED되면 host가 전파되고 v2로 하던 승인 판정은 그대로 동작한다.
 */
@EntitlementDbTest
class SchemaRolloutOrderV3Spec(
    private val context: WebApplicationContext,
    private val hosts: SiteWebHostPort,
    private val admission: AuthorizationAdmissionPort,
    private val transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()
    val tx = TransactionTemplate(transactionManager)
    val at = T0.plusSeconds(5)

    fun host(ids: SnapshotFixture.Ids): SiteWebHostResult = checkNotNull(tx.execute { hosts.resolve(MerchantAccountId(ids.mid), MerchantSiteId(ids.siteId), at) })

    fun admit(ids: SnapshotFixture.Ids): AdmissionResult = checkNotNull(
        tx.execute { admission.evaluate(MerchantAccountId(ids.mid), MerchantSiteId(ids.siteId), CurrencyCode("KRW"), PaymentMethod.CARD, SnapshotFixture.ACQUIRER, at) },
    )

    Given("reverse order: a schema version PAYMENT cannot read arrives on a v2 head") {
        Then("the head is blocked (UNSUPPORTED) and both the window host and the authorization admission fail closed") {
            val ids = SnapshotFixture.ids()
            send(mvc, body(SnapshotFixture.entitledWithTerms(ids, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            val newer = envelope(UUID.randomUUID().toString(), SnapshotFixture.UNSUPPORTED_SCHEMA_VERSION, ids.merchantId, 2, """{"futureField":true}""")
            send(mvc, newer, signature(newer)).json!!.get("outcome").stringValue() shouldBe "UNSUPPORTED"
            host(ids) shouldBe SiteWebHostResult.Unavailable(SiteWebHostUnavailable.VERSION_UNSUPPORTED)
            admit(ids).shouldBeInstanceOf<AdmissionResult.Unavailable>()
        }
    }

    Given("correct order: PAYMENT reads v3 before MASTER promotes") {
        Then("the v2 head refuses the host (HOST_NOT_PROPAGATED), the promotion issue (v3, revision + 1) is APPLIED and the host resolves; admission keeps working") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            send(mvc, body(SnapshotFixture.entitledWithTerms(ids, terms, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            host(ids) shouldBe SiteWebHostResult.Unavailable(SiteWebHostUnavailable.HOST_NOT_PROPAGATED)
            admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>()
            val promoted = send(mvc, body(SnapshotFixture.entitledWithChannels(ids, terms, revision = 2), SnapshotFixture.leaseAt(T0)))
            promoted.json!!.get("outcome").stringValue() shouldBe "APPLIED"
            host(ids).shouldBeInstanceOf<SiteWebHostResult.WebHost>().host shouldBe SnapshotFixture.WEB_HOST
            admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>()
        }
    }
})
