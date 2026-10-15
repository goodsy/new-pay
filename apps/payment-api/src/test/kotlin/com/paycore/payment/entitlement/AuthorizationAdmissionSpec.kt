package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.domain.ActivationStatus
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.Lease
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapTerms
import com.paycore.payment.entitlement.domain.SnapshotFixture.route
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmissionUnavailableReason
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.domain.AuthorizationAdmission
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 승인 시작 허용 판정 Port(MASTER-SNAPSHOT-002 명세 §6)를 실제 MySQL·운영 배선(Inbox → 투영 → entitlement 판정 → payment adapter)으로 확인한다.
 * 입력은 서명 Inbox로 받은 PAYMENT 로컬 투영뿐이다(MASTER 조회 없음). 값은 Demo.
 */
@EntitlementDbTest
class AuthorizationAdmissionSpec(
    private val context: WebApplicationContext,
    private val port: AuthorizationAdmissionPort,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
    private val faults: ProjectionFaultInjector,
    private val clock: MutableEntitlementClock,
    private val beans: ApplicationContext,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()
    val tx = TransactionTemplate(transactionManager)
    val at = T0.plusSeconds(5)

    afterEach {
        faults.reset()
        clock.reset()
    }

    fun propagate(content: EntitlementContent, lease: Lease = SnapshotFixture.leaseAt(T0)) = send(mvc, body(content, lease))

    fun admit(ids: SnapshotFixture.Ids, acquirer: String = SnapshotFixture.ACQUIRER, evaluatedAt: Instant = at): AdmissionResult = checkNotNull(
        tx.execute {
            port.evaluate(MerchantAccountId(ids.mid), MerchantSiteId(ids.siteId), CurrencyCode("KRW"), PaymentMethod.CARD, acquirer, evaluatedAt)
        },
    )

    fun headRevision(merchantId: String): Long =
        db.withApp { c -> with(db) { (c.scalar("SELECT revision FROM entitlement_head WHERE merchant_id = ?", merchantId) as Number).toLong() } }

    Given("the production wiring") {
        Then("the only AuthorizationAdmissionPort bean is the entitlement adapter, and it refuses to run outside a caller transaction (MANDATORY)") {
            beans.getBeansOfType(AuthorizationAdmissionPort::class.java).values.map { it::class.java.simpleName } shouldBe
                listOf("EntitlementAuthorizationAdmissionAdapter")
            val ids = SnapshotFixture.ids()
            shouldThrow<IllegalTransactionStateException> {
                port.evaluate(MerchantAccountId(ids.mid), MerchantSiteId(ids.siteId), CurrencyCode("KRW"), PaymentMethod.CARD, SnapshotFixture.ACQUIRER, at)
            }
        }
    }

    Given("a propagated v2 snapshot where authorization can start") {
        Then("Admitted: the entitlement version, the pa1 assignment string, the route and the CSA default limit scoped to MID + currency (D-AUTH-006)") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            propagate(SnapshotFixture.entitledWithTerms(ids, terms, revision = 3)).status shouldBe 200
            val admitted = admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>()
            listOf(admitted.entitlementVersion, admitted.policyAssignmentVersion, admitted.route.routeRef, admitted.route.acquirerCode, admitted.policyContentStatus) shouldBe
                listOf(
                    "es1:${ids.merchantId}:3",
                    "pa1:fee=${terms.feeAssignment}/${terms.feeVersion};stl=${terms.settlementAssignment}/${terms.settlementVersion}",
                    SnapshotFixture.ROUTE_REF, SnapshotFixture.ACQUIRER, setOf("DEMO"),
                )
            admitted.limitPolicy shouldBe LimitPolicyResolution.Resolved(
                TransactionLimitPolicy(terms.csaDefault, MerchantAccountId(ids.mid), CurrencyCode("KRW"), LimitAmount.Limited(1_000_000), LimitAmount.Limited(5_000_000)),
            )
            admitted.admission("SIM-PROTO-1") shouldBe AuthorizationAdmission("es1:${ids.merchantId}:3", admitted.policyAssignmentVersion, SnapshotFixture.ROUTE_REF, "SIM-PROTO-1", at)
            admitted.route.toString().contains(SnapshotFixture.INSTITUTION_REF) shouldBe false
        }

        Then("an acquirer without an ACTIVE card route is refused CARD_COMPANY_NOT_ENABLED; an unpropagated MID is SNAPSHOT_MISSING") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitledWithTerms(ids)).status shouldBe 200
            admit(ids, acquirer = "OTHERCARD") shouldBe AdmissionResult.Refused(AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED)
            admit(SnapshotFixture.ids()) shouldBe AdmissionResult.Unavailable(AdmissionUnavailableReason.SNAPSHOT_MISSING)
        }

        Then("after the lease expires (MASTER silent) the admission is SNAPSHOT_EXPIRED (fail-closed)") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitledWithTerms(ids)).status shouldBe 200
            clock.set(T0.plusSeconds(60))
            admit(ids, evaluatedAt = T0.plusSeconds(60)) shouldBe AdmissionResult.Unavailable(AdmissionUnavailableReason.SNAPSHOT_EXPIRED)
        }

        Then("the logs of a refused admission carry no institution merchant reference (D-MS2-005)") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitledWithTerms(ids)).status shouldBe 200
            CapturedLogs().use { logs ->
                admit(ids, acquirer = "OTHERCARD")
                admit(ids)
                logs.lines().none { it.contains(SnapshotFixture.INSTITUTION_REF) } shouldBe true
            }
        }
    }

    Given("a merchant whose head is still a v1 snapshot (before the schema promotion reaches PAYMENT)") {
        Then("creation eligibility keeps working but authorization start is TERMS_NOT_PROPAGATED; the promoted v2 revision makes it Admitted") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitled(ids, revision = 5)).status shouldBe 200
            admit(ids) shouldBe AdmissionResult.Unavailable(AdmissionUnavailableReason.TERMS_NOT_PROPAGATED)
            propagate(SnapshotFixture.entitledWithTerms(ids, revision = 6)).json!!.get("outcome").stringValue() shouldBe "APPLIED"
            admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>().entitlementVersion shouldBe "es1:${ids.merchantId}:6"
        }
    }

    Given("the same v2 event delivered 8 times at once (real MySQL, separate connections — review round1 m-2)") {
        Then("it is APPLIED once and DUPLICATE 7 times with one inbox row and one stored revision; the admission reads that revision") {
            val ids = SnapshotFixture.ids()
            val v2 = body(SnapshotFixture.entitledWithTerms(ids, revision = 4), SnapshotFixture.leaseAt(T0))
            val outcomes = concurrently(8) { send(mvc, v2) }.map { r -> r.getOrThrow().let { it.status to it.json!!.get("outcome").stringValue() } }
            outcomes.groupingBy { it }.eachCount() shouldBe mapOf((200 to "APPLIED") to 1, (200 to "DUPLICATE") to 7)
            db.withApp { c ->
                with(db) {
                    listOf(
                        (c.scalar("SELECT COUNT(*) FROM inbox_event WHERE aggregate_id = ?", ids.merchantId) as Number).toLong(),
                        (c.scalar("SELECT COUNT(*) FROM entitlement_snapshot WHERE merchant_id = ? AND schema_version = 2", ids.merchantId) as Number).toLong(),
                    )
                }
            } shouldBe listOf(1L, 1L)
            admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>().entitlementVersion shouldBe "es1:${ids.merchantId}:4"
        }
    }

    Given("a revision that suspends the card route arriving before an older one that allowed it (order reversal)") {
        Then("the older revision is STALE and cannot reopen the route; the same eventId sent again is DUPLICATE and changes nothing") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            val suspended = SnapshotFixture.entitledWithTerms(ids, terms, revision = 3).mapTerms { it.copy(cardRoutes = listOf(route(terms.route, ActivationStatus.SUSPENDED))) }
            val newer = body(suspended, SnapshotFixture.leaseAt(T0))
            send(mvc, newer).json!!.get("outcome").stringValue() shouldBe "APPLIED"
            propagate(SnapshotFixture.entitledWithTerms(ids, terms, revision = 2)).json!!.get("outcome").stringValue() shouldBe "STALE"
            send(mvc, newer).json!!.get("outcome").stringValue() shouldBe "DUPLICATE"
            headRevision(ids.merchantId) shouldBe 3L
            admit(ids) shouldBe AdmissionResult.Refused(AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED)
        }
    }

    Given("a route suspension arriving while an authorization start is evaluating with the previous terms") {
        Then("the inbox waits for the in-flight evaluation (head FOR SHARE vs FOR UPDATE); afterwards the admission sees the suspension") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            propagate(SnapshotFixture.entitledWithTerms(ids, terms, revision = 1)).status shouldBe 200
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(ProjectionFaultPoint.HEAD_SHARE) {
                if (Thread.currentThread().name == "held-admission") {
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            val inFlight = CompletableFuture<AdmissionResult>()
            Thread({ inFlight.complete(runCatching { admit(ids) }.getOrElse { inFlight.completeExceptionally(it); return@Thread }) }, "held-admission").start()
            held.await(30, TimeUnit.SECONDS) shouldBe true
            faults.reset()
            val suspension = SnapshotFixture.entitledWithTerms(ids, terms, revision = 2).mapTerms { it.copy(cardRoutes = listOf(route(terms.route, ActivationStatus.SUSPENDED))) }
            val inbox = Thread { send(mvc, body(suspension, SnapshotFixture.leaseAt(T0))) }.also { it.start() }
            db.awaitLockWaiters(1)
            headRevision(ids.merchantId) shouldBe 1L
            release.countDown()
            inFlight.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<AdmissionResult.Admitted>()
            inbox.join(30_000)
            headRevision(ids.merchantId) shouldBe 2L
            admit(ids) shouldBe AdmissionResult.Refused(AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED)
        }
    }
})
