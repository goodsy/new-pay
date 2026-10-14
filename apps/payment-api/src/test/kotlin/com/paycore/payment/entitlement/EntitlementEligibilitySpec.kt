package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.domain.ActivationEntry
import com.paycore.payment.entitlement.domain.ActivationStatus
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapAccount
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.application.CreatePaymentFixture
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.EligibilityRefusal
import com.paycore.payment.payment.application.provided.EligibilityUnavailableReason
import com.paycore.payment.payment.application.provided.NotEligibleReason
import com.paycore.payment.payment.application.required.PaymentEligibilityPort
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 운영 eligibility 구현으로 CreatePayment를 실제 MySQL에서 실행한다(EligibilityTestConfig 없음 — L-11). 판정 입력은 Inbox로 받은 PAYMENT 로컬 투영뿐이다.
 * 차단 Snapshot 적용(head FOR UPDATE)은 이전 권리로 평가 중인 CreatePayment(head FOR SHARE)가 끝날 때까지 기다리고, 그 뒤의 생성은 새 권리로
 * 판정된다(명세 §3.4-4).
 */
@EntitlementDbTest
class EntitlementEligibilitySpec(
    private val context: WebApplicationContext,
    private val createPayment: CreatePaymentUseCase,
    private val db: DbAccess,
    private val faults: ProjectionFaultInjector,
    private val clock: MutableEntitlementClock,
    private val beans: ApplicationContext,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()
    val at = T0.plusSeconds(5)

    afterEach {
        faults.reset()
        clock.reset()
    }

    fun order(ids: SnapshotFixture.Ids, environment: Environment = Environment.LIVE) =
        OrderKey(environment, MerchantAccountId(ids.mid), MerchantOrderId("ord-" + UUID.randomUUID().toString().substring(0, 12)))

    fun create(ids: SnapshotFixture.Ids, environment: Environment = Environment.LIVE, currency: String = "KRW", requestedAt: java.time.Instant = at) =
        createPayment.create(
            CreatePaymentFixture.command(
                order(ids, environment), OrderIntent(MerchantSiteId(ids.siteId), Money(10_000, CurrencyCode(currency))), at = requestedAt,
            ),
        )

    fun propagate(content: com.paycore.payment.entitlement.domain.EntitlementContent, lease: com.paycore.payment.entitlement.domain.Lease = SnapshotFixture.leaseAt(T0)) =
        send(mvc, body(content, lease)).status shouldBe 200

    Given("the production wiring") {
        Then("the only PaymentEligibilityPort bean is the entitlement adapter (no test double in this context)") {
            beans.getBeansOfType(PaymentEligibilityPort::class.java).values.map { it::class.java.simpleName } shouldBe listOf("EntitlementEligibilityAdapter")
        }
    }

    Given("a propagated entitled merchant") {
        Then("CreatePayment is CREATED and the payment keeps the snapshot version es1:<merchant>:<revision>; TEST is judged like LIVE (D-MS-001)") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitled(ids, revision = 4))
            listOf(Environment.LIVE, Environment.TEST).map { env ->
                create(ids, env).shouldBeInstanceOf<CreatePaymentResult.Accepted>().outcome
            } shouldBe listOf(CreatePaymentOutcome.CREATED, CreatePaymentOutcome.CREATED)
            db.withApp { c -> with(db) { c.rows("SELECT DISTINCT eligibility_snapshot_version v FROM payment WHERE merchant_account_id = ?", ids.mid) } }
                .map { it["v"] } shouldBe listOf("es1:${ids.merchantId}:4")
        }

        Then("refusals come from the snapshot: USD not enabled, and after a blocking revision METHOD_NOT_ENABLED for both TEST and LIVE") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitled(ids, revision = 1))
            create(ids, currency = "USD") shouldBe CreatePaymentResult.EligibilityRefused(EligibilityRefusal.NotEligible(NotEligibleReason.CURRENCY_NOT_ENABLED))
            propagate(SnapshotFixture.entitled(ids, revision = 2).mapAccount { it.copy(activations = listOf(ActivationEntry("CARD", ActivationStatus.SUSPENDED))) })
            listOf(Environment.LIVE, Environment.TEST).map { create(ids, it) }.toSet() shouldBe
                setOf(CreatePaymentResult.EligibilityRefused(EligibilityRefusal.NotEligible(NotEligibleReason.METHOD_NOT_ENABLED)))
        }

        Then("a MID that was never propagated is SNAPSHOT_MISSING (fail-closed, nothing stored)") {
            create(SnapshotFixture.ids()) shouldBe CreatePaymentResult.EligibilityRefused(EligibilityRefusal.Unavailable(EligibilityUnavailableReason.SNAPSHOT_MISSING))
        }

        Then("when the lease runs out (no renewal) creation is SNAPSHOT_EXPIRED; a renewal of the same revision restores it") {
            val ids = SnapshotFixture.ids()
            val content = SnapshotFixture.entitled(ids)
            propagate(content)
            val late = T0.plusSeconds(60)
            clock.set(late)
            create(ids, requestedAt = late) shouldBe
                CreatePaymentResult.EligibilityRefused(EligibilityRefusal.Unavailable(EligibilityUnavailableReason.SNAPSHOT_EXPIRED))
            propagate(content, SnapshotFixture.leaseAt(late, seq = 1))
            create(ids, requestedAt = late).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        }
    }

    Given("mixed contention with the production adapter (review m-1): creations on one merchant and inbox applies at the same time") {
        Then("no deadlock, no double payment for one order, every payment carries a pre-block revision, and after the blocking ACK creation is refused") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitled(ids, revision = 1))
            val sharedOrder = order(ids)
            fun createFor(o: OrderKey) = createPayment.create(
                CreatePaymentFixture.command(o, OrderIntent(MerchantSiteId(ids.siteId), Money(10_000, CurrencyCode("KRW"))), at = at),
            )
            fun deadlocks(): Long = db.withRoot { c ->
                with(db) { (c.scalar("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'") as Number).toLong() }
            }
            val deadlocksBefore = deadlocks()
            // 생성 하나가 head S 잠금을 쥔 채 멈춘 동안 나머지(생성·Inbox)를 출발시켜 실제 잠금 대기를 만든다.
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(ProjectionFaultPoint.HEAD_SHARE) {
                if (Thread.currentThread().name == "held-creation") {
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            val first = java.util.concurrent.CompletableFuture<CreatePaymentResult>()
            Thread({ runCatching { createFor(sharedOrder) }.fold(first::complete, first::completeExceptionally) }, "held-creation").start()
            held.await(30, TimeUnit.SECONDS) shouldBe true
            val revisions = listOf(
                SnapshotFixture.entitled(ids, revision = 2),
                SnapshotFixture.entitled(ids, revision = 3),
                SnapshotFixture.entitled(ids, revision = 4).mapAccount { it.copy(activations = listOf(ActivationEntry("CARD", ActivationStatus.SUSPENDED))) },
            )
            val rest = java.util.concurrent.CompletableFuture.supplyAsync {
                concurrently(8) { i ->
                    when {
                        i < 3 -> send(mvc, body(revisions[i], SnapshotFixture.leaseAt(T0))).status
                        i < 5 -> createFor(sharedOrder)
                        else -> createFor(order(ids))
                    }
                }
            }
            db.awaitLockWaiters(1)
            release.countDown()
            val outcomes = listOf(Result.success(first.get(60, TimeUnit.SECONDS))) + rest.get(90, TimeUnit.SECONDS)
            outcomes.all { it.isSuccess } shouldBe true
            outcomes.map { it.getOrThrow() }.filterIsInstance<Int>() shouldBe listOf(200, 200, 200)
            deadlocks() shouldBe deadlocksBefore
            val rows = db.withApp { c ->
                with(db) {
                    c.rows("SELECT merchant_order_id o, eligibility_snapshot_version v FROM payment WHERE merchant_account_id = ?", ids.mid)
                }
            }
            rows.count { it["o"] == sharedOrder.merchantOrderId.value } shouldBe 1
            rows.map { it["v"] }.toSet().all { it in setOf("es1:${ids.merchantId}:1", "es1:${ids.merchantId}:2", "es1:${ids.merchantId}:3") } shouldBe true
            db.withApp { c -> with(db) { (c.scalar("SELECT revision FROM entitlement_head WHERE merchant_id = ?", ids.merchantId) as Number).toLong() } } shouldBe 4L
            create(ids) shouldBe CreatePaymentResult.EligibilityRefused(EligibilityRefusal.NotEligible(NotEligibleReason.METHOD_NOT_ENABLED))
        }
    }

    Given("a blocking snapshot arriving while a CreatePayment is evaluating with the previous rights") {
        Then("the inbox waits for the in-flight creation (head FOR SHARE vs FOR UPDATE); after its ACK no creation uses the old rights") {
            val ids = SnapshotFixture.ids()
            propagate(SnapshotFixture.entitled(ids, revision = 1))
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(ProjectionFaultPoint.HEAD_SHARE) {
                if (Thread.currentThread().name == "held-creation") {
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            val creation = java.util.concurrent.CompletableFuture<CreatePaymentResult>()
            Thread({ creation.complete(runCatching { create(ids) }.getOrElse { creation.completeExceptionally(it); return@Thread }) }, "held-creation").start()
            held.await(30, TimeUnit.SECONDS) shouldBe true
            // 판정 중인 생성 TX가 head를 공유(S) 잠금으로 쥐고 있다(MySQL `FOR SHARE`).
            db.withRoot { c ->
                with(db) {
                    c.rows(
                        "SELECT LOCK_MODE m FROM performance_schema.data_locks WHERE OBJECT_NAME = 'entitlement_head' AND LOCK_TYPE = 'RECORD'",
                    ).map { it["m"].toString() }.any { it.startsWith("S") }
                }
            } shouldBe true
            faults.reset()
            val blocking = SnapshotFixture.entitled(ids, revision = 2).mapAccount { it.copy(activations = listOf(ActivationEntry("CARD", ActivationStatus.SUSPENDED))) }
            val inbox = Thread { send(mvc, body(blocking, SnapshotFixture.leaseAt(T0))) }.also { it.start() }
            db.awaitLockWaiters(1)
            release.countDown()
            creation.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            inbox.join(30_000)
            db.withApp { c -> with(db) { (c.scalar("SELECT revision FROM entitlement_head WHERE merchant_id = ?", ids.merchantId) as Number).toLong() } } shouldBe 2L
            create(ids) shouldBe CreatePaymentResult.EligibilityRefused(EligibilityRefusal.NotEligible(NotEligibleReason.METHOD_NOT_ENABLED))
        }
    }
})
