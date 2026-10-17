package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.Lease
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.payment.application.required.SiteWebHostPort
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.application.required.SiteWebHostUnavailable
import com.paycore.payment.payment.application.required.NoWebHostCause
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Instant

/**
 * Site WEB host 조회(AUTHN-001 §9.4)를 실제 MySQL·운영 배선(서명 Inbox → 투영 → entitlement `SiteWebHostQuery` → payment `SiteWebHostPort` adapter)으로
 * 확인한다. 호출자 TX(MANDATORY)에서만, PAYMENT 로컬 투영만 읽는다. 값은 Demo.
 */
@EntitlementDbTest
class SiteWebHostQuerySpec(
    private val context: WebApplicationContext,
    private val port: SiteWebHostPort,
    private val transactionManager: PlatformTransactionManager,
    private val clock: MutableEntitlementClock,
    private val beans: ApplicationContext,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()
    val tx = TransactionTemplate(transactionManager)
    val at = T0.plusSeconds(5)

    afterEach { clock.reset() }

    fun propagate(content: EntitlementContent, lease: Lease = SnapshotFixture.leaseAt(T0)) = send(mvc, body(content, lease))

    fun resolve(ids: SnapshotFixture.Ids, site: String = ids.siteId, evaluatedAt: Instant = at): SiteWebHostResult =
        checkNotNull(tx.execute { port.resolve(MerchantAccountId(ids.mid), MerchantSiteId(site), evaluatedAt) })

    Given("the production wiring") {
        Then("the only SiteWebHostPort bean is the entitlement adapter and it refuses to run outside a caller transaction (MANDATORY)") {
            beans.getBeansOfType(SiteWebHostPort::class.java).values.map { it::class.java.simpleName } shouldBe listOf("EntitlementSiteWebHostAdapter")
            val ids = SnapshotFixture.ids()
            shouldThrow<IllegalTransactionStateException> { port.resolve(MerchantAccountId(ids.mid), MerchantSiteId(ids.siteId), at) }
        }
    }

    Given("a propagated v3 snapshot") {
        Then("the WEB site resolves to its host with the snapshot version; the APP site and an unknown site have no host") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitledWithChannels(ids, revision = 2)).status shouldBe 200
            resolve(ids) shouldBe SiteWebHostResult.WebHost(SnapshotFixture.WEB_HOST, "es1:${ids.merchantId}:2", SnapshotFixture.leaseAt(T0).leaseUntil.minus(SnapshotFixture.skew))
            resolve(ids, site = ids.otherSiteId) shouldBe SiteWebHostResult.NoWebHost(NoWebHostCause.APP_CHANNEL)
            resolve(ids, site = "site_unknown") shouldBe SiteWebHostResult.NoWebHost(NoWebHostCause.SITE_UNKNOWN)
        }
        Then("after the lease expires the host is unavailable (fail-closed)") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitledWithChannels(ids)).status shouldBe 200
            clock.set(T0.plusSeconds(60))
            resolve(ids, evaluatedAt = T0.plusSeconds(60)) shouldBe SiteWebHostResult.Unavailable(SiteWebHostUnavailable.SNAPSHOT_EXPIRED)
        }
    }

    Given("a v2 head (MASTER not yet on v3) or an unpropagated MID") {
        Then("HOST_NOT_PROPAGATED / SNAPSHOT_MISSING — token issuance is refused until the promotion issue (v3, revision + 1) is applied") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitledWithTerms(ids)).status shouldBe 200
            resolve(ids) shouldBe SiteWebHostResult.Unavailable(SiteWebHostUnavailable.HOST_NOT_PROPAGATED)
            propagate(SnapshotFixture.entitledWithChannels(ids, revision = 2)).status shouldBe 200
            (resolve(ids) is SiteWebHostResult.WebHost) shouldBe true
            resolve(SnapshotFixture.ids()) shouldBe SiteWebHostResult.Unavailable(SiteWebHostUnavailable.SNAPSHOT_MISSING)
        }
    }
})
