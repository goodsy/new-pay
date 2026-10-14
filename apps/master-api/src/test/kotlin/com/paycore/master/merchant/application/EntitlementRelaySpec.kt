package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.integration.EntitlementEnvelope
import com.paycore.master.merchant.adapter.integration.MicrometerMasterOperationalMetrics
import com.paycore.master.merchant.application.EntitlementCommandFixture.ackCount
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.reason
import com.paycore.master.merchant.application.EntitlementCommandFixture.snapshotOutbox
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.EffectBasis
import com.paycore.master.merchant.application.provided.EntitlementLeaseUseCase
import com.paycore.master.merchant.application.provided.EntitlementPropagationQuery
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.SuspendPaymentMethodActivationCommand
import com.paycore.master.merchant.application.required.EntitlementDispatcher
import com.paycore.master.merchant.application.required.EntitlementRelayStore
import com.paycore.master.merchant.application.required.EntitlementTiming
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Outbox Relay·lease 갱신·효력 확인(MASTER-SNAPSHOT-001 명세 §3.3–§3.4, §4.7–§4.8)을 실제 MySQL과 실제 HTTP Dispatcher(가짜 PAYMENT Inbox)로
 * 확인한다. 전달은 DB TX 밖, 결과는 행마다 별도 TX, 재전송은 같은 eventId(at-least-once — 수신 측 DUPLICATE).
 *
 * 같은 컨텍스트의 다른 시험이 만든 PENDING Snapshot도 같은 Outbox에 있으므로 각 시험은 자기 Merchant의 이벤트만 판정한다.
 */
@MasterDbTest
class EntitlementRelaySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val relay: EntitlementRelayService,
    private val leases: EntitlementLeaseUseCase,
    private val propagation: EntitlementPropagationQuery,
    private val inbox: FakePaymentInbox,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val registry: MeterRegistry,
    private val transactionManager: PlatformTransactionManager,
    private val relayStore: EntitlementRelayStore,
    private val dispatcher: EntitlementDispatcher,
    private val timing: EntitlementTiming,
    private val metrics: MasterOperationalMetrics,
    private val ids: MasterIdGenerator,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
    }

    /** 모든 PENDING을 비울 때까지 Relay를 돌린다(다른 시험 잔여 포함). */
    fun drain() {
        repeat(MAX_ROUNDS) { if (relay.relayOnce().claimed == 0) return }
    }

    fun counter(name: String, vararg tags: String): Double = registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    Given("snapshots issued by commands") {
        Then("the relay delivers each event once with a valid HMAC signature, records PUBLISHED and the ACK") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            drain()
            val issues = issuesOf(container, entitled.merchantId)
            val received = inbox.receivedFor(entitled.merchantId.value)
            received.map { it.eventId } shouldContainExactly issues.map { it.eventId }
            val key = Base64.getDecoder().decode("dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh")
            received.all { it.signature == EntitlementEnvelope.signatureHeader("test-k1", key, it.body) } shouldBe true
            issues.map { snapshotOutbox(container, it.eventId).publishStatus to ackCount(container, it.eventId) }.toSet() shouldBe setOf("PUBLISHED" to 1L)
        }

        Then("HIGH (change) snapshots are delivered before LOW (renewal) ones in a round") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            drain()
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            leases.renewDueLeases()
            succeeded(entitlement.suspendPaymentMethodActivation(SuspendPaymentMethodActivationCommand(request(), entitled.activationId, reason)))
            inbox.received.clear()
            drain()
            val mine = inbox.receivedFor(entitled.merchantId.value)
            val priorities = mine.map { r -> issuesOf(container, entitled.merchantId).single { it.eventId == r.eventId }.priority }
            priorities shouldBe listOf("HIGH", "LOW")
        }

        Then("a 5xx is retried later with the same eventId; a 4xx is quarantined (not completed) and counted") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val issues = issuesOf(container, entitled.merchantId)
            val retried = issues[0].eventId
            val quarantined = issues[1].eventId
            inbox.script(retried, FakePaymentInbox.Reply(503))
            inbox.script(quarantined, FakePaymentInbox.Reply(401))
            val quarantinedBefore = counter(MicrometerMasterOperationalMetrics.RELAY_RESULTS, "result", "QUARANTINED")
            drain()
            snapshotOutbox(container, retried).let { Triple(it.publishStatus, it.attemptCount, it.lastFailure) } shouldBe Triple("PENDING", 1, "HTTP_503")
            snapshotOutbox(container, quarantined).let { it.publishStatus to it.lastFailure } shouldBe ("QUARANTINED" to "HTTP_401")
            counter(MicrometerMasterOperationalMetrics.RELAY_RESULTS, "result", "QUARANTINED") shouldBe quarantinedBefore + 1

            clock.set(OnboardingFixture.t0.plusSeconds(1))
            drain()
            snapshotOutbox(container, retried).publishStatus shouldBe "PUBLISHED"
            inbox.receivedFor(entitled.merchantId.value).count { it.eventId == retried } shouldBe 2
            inbox.receivedFor(entitled.merchantId.value).count { it.eventId == quarantined } shouldBe 1
            (propagation.overview().quarantinedSnapshots >= 1) shouldBe true
            // 격리 gauge(명세 §11)는 조회 시점에 같은 jOOQ 조회로 계산된다.
            registry.get(MicrometerMasterOperationalMetrics.QUARANTINED).gauge().value() shouldBe propagation.overview().quarantinedSnapshots.toDouble()
        }

        Then("a lost response (read timeout after the receiver committed) is resent with the same eventId: DUPLICATE ACK, PUBLISHED once") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val event = issuesOf(container, entitled.merchantId).last().eventId
            inbox.script(event, FakePaymentInbox.Reply(200, delayMillis = 1200))
            drain()
            snapshotOutbox(container, event).let { it.publishStatus to it.lastFailure } shouldBe ("PENDING" to "TIMEOUT")
            clock.set(OnboardingFixture.t0.plusSeconds(1))
            drain()
            snapshotOutbox(container, event).publishStatus shouldBe "PUBLISHED"
            ackCount(container, event) shouldBe 1
            runtimeScalar(container, "SELECT CONCAT(outcome, '/', original_outcome) FROM entitlement_snapshot_ack WHERE outbox_event_id = ?", event) shouldBe
                "DUPLICATE/APPLIED"
        }

        Then("one row failing unexpectedly does not stall the rest of the round (review n-6); it is retried after its claim expires") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val events = issuesOf(container, entitled.merchantId).map { it.eventId }
            val broken = events.first()
            faults.after(MasterFaultPoint.RELAY_MARK) {
                if (inbox.received.lastOrNull()?.eventId == broken) throw IllegalStateException("injected mark failure")
            }
            drain()
            faults.reset()
            snapshotOutbox(container, broken).publishStatus shouldBe "PENDING"
            events.drop(1).map { snapshotOutbox(container, it).publishStatus }.toSet() shouldBe setOf("PUBLISHED")
            clock.set(OnboardingFixture.t0.plus(timing.claimLease).plusMillis(1))
            drain()
            snapshotOutbox(container, broken).publishStatus shouldBe "PUBLISHED"
        }
    }

    Given("two relay instances") {
        Then("they never claim the same row (FOR UPDATE SKIP LOCKED): a claim held open by one is skipped by the other") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            drain()
            succeeded(entitlement.suspendPaymentMethodActivation(SuspendPaymentMethodActivationCommand(request(), entitled.activationId, reason)))
            val second = EntitlementRelayService(transactionManager, relayStore, dispatcher, timing, clock, metrics, ids)
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(MasterFaultPoint.RELAY_CLAIM) { result ->
                if ((result as List<*>).isNotEmpty() && Thread.currentThread().name.startsWith("pool")) {
                    holding.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            val first = OnboardingFixture.concurrently(1) { relay.relayOnce() }
            holding.await(20, TimeUnit.SECONDS) shouldBe true
            faults.reset()
            val otherReport = second.relayOnce()
            release.countDown()
            val firstReport = first.single().getOrThrow()
            (firstReport.claimed + otherReport.claimed >= 1) shouldBe true
            val event = issuesOf(container, entitled.merchantId).last().eventId
            inbox.receivedFor(entitled.merchantId.value).count { it.eventId == event } shouldBe 1
            snapshotOutbox(container, event).publishStatus shouldBe "PUBLISHED"
        }

        Then("a claim left by a crashed relay expires and another instance redelivers the same eventId") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            drain()
            succeeded(entitlement.suspendPaymentMethodActivation(SuspendPaymentMethodActivationCommand(request(), entitled.activationId, reason)))
            val event = issuesOf(container, entitled.merchantId).last().eventId
            // 첫 Relay가 claim TX를 커밋한 뒤 전달 전에 죽었다(claim만 남음).
            LocalTransactions(transactionManager, 1).execute({ false }) {
                relayStore.claim("crashed-relay", OnboardingFixture.t0, OnboardingFixture.t0.plus(timing.claimLease), 500)
            }
            drain()
            inbox.receivedFor(entitled.merchantId.value).none { it.eventId == event } shouldBe true
            clock.set(OnboardingFixture.t0.plus(timing.claimLease).plusMillis(1))
            drain()
            snapshotOutbox(container, event).publishStatus shouldBe "PUBLISHED"
            inbox.receivedFor(entitled.merchantId.value).count { it.eventId == event } shouldBe 1
        }
    }

    Given("lease renewal (Demo lease 2s, renew-before 1s)") {
        Then("a due lease is renewed once with the same revision, next leaseSeq, priority LOW and the same content hash; a fresh lease is skipped") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val latest = issuesOf(container, entitled.merchantId).last()
            clock.set(OnboardingFixture.t0.plusMillis(500))
            leases.renewDueLeases()
            issuesOf(container, entitled.merchantId).last() shouldBe latest
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            leases.renewDueLeases()
            val renewed = issuesOf(container, entitled.merchantId).last()
            listOf(renewed.revision, renewed.leaseSeq) shouldBe listOf(latest.revision, 1L)
            (renewed.priority to renewed.contentHash) shouldBe ("LOW" to latest.contentHash)
        }

        Then("two concurrent renewal runs issue exactly one renewal for the merchant (merchant lock)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val before = issuesOf(container, entitled.merchantId).size
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            OnboardingFixture.concurrently(2) { leases.renewDueLeases() }.forEach { it.getOrThrow() }
            issuesOf(container, entitled.merchantId).size shouldBe before + 1
        }

        Then("after a suspension the renewal carries the restricted state — it never revives a past permission (R4)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            succeeded(entitlement.suspendPaymentMethodActivation(SuspendPaymentMethodActivationCommand(request(), entitled.activationId, reason)))
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            leases.renewDueLeases()
            val renewed = issuesOf(container, entitled.merchantId).last()
            renewed.leaseSeq shouldBe 1L
            snapshotOutbox(container, renewed.eventId).payload.get("accounts").single().get("activations").single().get("status").stringValue() shouldBe
                "SUSPENDED"
        }
    }

    Given("effect confirmation of the latest revision (R6, §3.4-5)") {
        Then("a DUPLICATE ACK whose original outcome was UNSUPPORTED confirms like the first UNSUPPORTED answer (review m-3)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val r = issuesOf(container, entitled.merchantId).last()
            inbox.script(r.eventId, FakePaymentInbox.Reply(200, outcome = "DUPLICATE", appliedRevision = r.revision - 1, originalOutcome = "UNSUPPORTED"))
            drain()
            val view = propagation.merchant(entitled.merchantId)!!
            (view.effectBasis to view.latestRevision) shouldBe (EffectBasis.ACK to r.revision)
        }

        Then("publication alone is not confirmation; an ACK with appliedRevision ≥ r confirms with basis ACK") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val r = issuesOf(container, entitled.merchantId).last()
            val event = r.eventId
            inbox.script(event, FakePaymentInbox.Reply(200, outcome = "STALE", appliedRevision = r.revision - 1))
            drain()
            propagation.merchant(entitled.merchantId)!!.let { it.effectConfirmedAt to it.effectBasis } shouldBe (null to null)
            succeeded(entitlement.suspendPaymentMethodActivation(SuspendPaymentMethodActivationCommand(request(), entitled.activationId, reason)))
            drain()
            val view = propagation.merchant(entitled.merchantId)!!
            (view.effectBasis to view.latestAckRevision) shouldBe (EffectBasis.ACK to r.revision + 1)
        }

        Then("without any ACK the revision is confirmed by the expiry of every earlier lease (basis LEASE_EXPIRY)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val events = issuesOf(container, entitled.merchantId).map { it.eventId }
            events.forEach { inbox.script(it, FakePaymentInbox.Reply(503), FakePaymentInbox.Reply(503), FakePaymentInbox.Reply(503)) }
            drain()
            propagation.merchant(entitled.merchantId)!!.effectBasis shouldBe null
            clock.set(OnboardingFixture.t0.plusSeconds(2))
            val view = propagation.merchant(entitled.merchantId)!!
            (view.effectBasis to view.effectConfirmedAt) shouldBe (EffectBasis.LEASE_EXPIRY to view.priorLeaseMaxUntil)
        }
    }
}) {
    private companion object {
        const val MAX_ROUNDS = 50

        fun runtimeScalar(container: MySQLContainer, sql: String, vararg args: Any?): Any? = OnboardingFixture.runtime(container).use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
            }
        }
    }
}
