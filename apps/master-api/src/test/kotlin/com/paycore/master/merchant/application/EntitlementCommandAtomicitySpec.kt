package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.integration.MicrometerMasterOperationalMetrics
import com.paycore.master.merchant.application.EntitlementCommandFixture.evidence
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.reason
import com.paycore.master.merchant.application.EntitlementCommandFixture.revisionOf
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApprovePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.TerminatePaymentMethodActivationCommand
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.mysql.MySQLContainer

/**
 * 권리 명령의 원자성·경합·운영 지표(MASTER-SNAPSHOT-001 §11 Transaction Safety·Concurrency·Observability, review m-7·m-8)를 실제 MySQL 다중 연결로 확인한다.
 * 개통 승인은 업무 행(activation·이력·PRIMARY 확인)·revision·참조 이벤트·Snapshot 발행(issue + Outbox)·요청 결과를 한 TX에 쓴다 — 어느 저장 지점 뒤에
 * 실패해도 아무것도 남지 않는다.
 */
@MasterDbTest
class EntitlementCommandAtomicitySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val faults: MasterFaultInjector,
    private val ids: ScriptedIdGenerator,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    afterEach {
        faults.reset()
        ids.reset()
    }

    class Injected(point: String) : RuntimeException("injected failure after $point")

    data class State(val status: String, val confirmations: Long, val revision: Long, val issues: Int, val events: Long, val requests: Long)

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun stateOf(ready: OnboardingFixture.ReadyMerchant, activation: PaymentMethodActivationId, key: String): State = State(
        status = runtime(container).use { c ->
            c.prepareStatement("SELECT status FROM payment_method_activation WHERE activation_id = ?").use { ps ->
                ps.setString(1, activation.value)
                ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        },
        confirmations = count("SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?", activation.value),
        revision = revisionOf(container, ready.merchantId),
        issues = issuesOf(container, ready.merchantId).size,
        events = count("SELECT COUNT(*) FROM payment_method_activation_event WHERE activation_id = ?", activation.value),
        requests = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", key),
    )

    fun counter(kind: String, operation: String): Double =
        registry.find(MicrometerMasterOperationalMetrics.ADMIN_FAILURES).tags("kind", kind, "operation", operation).counter()?.count() ?: 0.0

    Given("an activation approval with a failure injected after each storage point") {
        val points = listOf(
            MasterFaultPoint.ACTIVATION_UPDATE, MasterFaultPoint.CONFIRMATION_INSERT, MasterFaultPoint.MERCHANT_UPDATE,
            MasterFaultPoint.CHANGE_LOG_APPEND, MasterFaultPoint.OUTBOX_APPEND, MasterFaultPoint.ISSUE_RECORD, MasterFaultPoint.ADMIN_COMPLETE,
        )
        points.forEach { point ->
            Then("a failure after $point leaves nothing (activation, confirmation, revision, snapshot issue, history, request) and the same key then succeeds") {
                val (ready, ids2) = EntitlementCommandFixture.issuedWithActivation(onboarding)
                val activation = ids2.second
                val command = ApprovePaymentMethodActivationCommand(request(), activation, evidence)
                val before = stateOf(ready, activation, command.request.idempotencyKey.value)
                faults.after(point) { throw Injected(point.name) }
                shouldThrow<Injected> { entitlement.approvePaymentMethodActivation(command) }
                stateOf(ready, activation, command.request.idempotencyKey.value) shouldBe before
                faults.reset()
                (entitlement.approvePaymentMethodActivation(command) as AdminCommandResult.Succeeded).replayed shouldBe false
                stateOf(ready, activation, command.request.idempotencyKey.value) shouldBe before.copy(
                    status = "ACTIVE", confirmations = 1, revision = before.revision + 1, issues = before.issues + 1, events = before.events + 1,
                    requests = 1,
                )
            }
        }

        Then("a failure right before commit is OutcomeUnknown, leaves nothing and is counted (review m-7·m-8)") {
            val (ready, ids2) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val command = ApprovePaymentMethodActivationCommand(request(), ids2.second, evidence)
            val before = stateOf(ready, ids2.second, command.request.idempotencyKey.value)
            val unknownBefore = counter("outcome_unknown", "ApprovePaymentMethodActivation")
            faults.after(MasterFaultPoint.ADMIN_COMPLETE) {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = throw Injected("beforeCommit")
                    },
                )
            }
            shouldThrow<AdminCommandFailure.OutcomeUnknown> { entitlement.approvePaymentMethodActivation(command) }
            stateOf(ready, ids2.second, command.request.idempotencyKey.value) shouldBe before
            counter("outcome_unknown", "ApprovePaymentMethodActivation") shouldBe unknownBefore + 1
        }
    }

    Given("technical failures that the executor classifies") {
        Then("a deadlock on every attempt exhausts the retries (TemporarilyUnavailable) and is counted once") {
            val (_, ids2) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val before = counter("retries_exhausted", "ApprovePaymentMethodActivation")
            faults.after(MasterFaultPoint.ACTIVATION_UPDATE) { throw PersistenceFailure.Deadlock(RuntimeException("injected deadlock")) }
            shouldThrow<AdminCommandFailure.TemporarilyUnavailable> {
                entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(request(), ids2.second, evidence))
            }
            counter("retries_exhausted", "ApprovePaymentMethodActivation") shouldBe before + 1
        }

        Then("a server-generated confirmation id colliding with an existing row is IdentifierCollision, stores nothing and is counted") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val existing = runtime(container).use { c ->
                c.prepareStatement("SELECT confirmation_id FROM site_activation_confirmation WHERE activation_id = ?").use { ps ->
                    ps.setString(1, entitled.activationId.value)
                    ps.executeQuery().use { rs -> rs.next(); SiteActivationConfirmationId(rs.getString(1)) }
                }
            }
            val (ready, ids2) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val command = ApprovePaymentMethodActivationCommand(request(), ids2.second, evidence)
            val before = stateOf(ready, ids2.second, command.request.idempotencyKey.value)
            val collisions = counter("identifier_collision", "ApprovePaymentMethodActivation")
            ids.forceNext(SiteActivationConfirmationId.PREFIX, existing.value)
            shouldThrow<AdminCommandFailure.IdentifierCollision> { entitlement.approvePaymentMethodActivation(command) }.kind shouldBe "IDENTIFIER"
            stateOf(ready, ids2.second, command.request.idempotencyKey.value) shouldBe before
            counter("identifier_collision", "ApprovePaymentMethodActivation") shouldBe collisions + 1
        }
    }

    Given("concurrent approve and terminate commands on the same activation (different keys)") {
        Then("they serialize on the merchant row: the history is a valid transition chain and each success advanced the revision and published once") {
            val (ready, ids2) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val activation = ids2.second
            val before = stateOf(ready, activation, "none")
            val results = OnboardingFixture.concurrently(6) { i ->
                if (i % 2 == 0) {
                    entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(request(), activation, EvidenceRef("van-reply:race-$i")))
                } else {
                    entitlement.terminatePaymentMethodActivation(TerminatePaymentMethodActivationCommand(request(), activation, reason))
                }
            }.map { it.getOrThrow() }
            val successes = results.count { it is AdminCommandResult.Succeeded }
            results.filterIsInstance<AdminCommandResult.Rejected>().map { it.rejection }.toSet().all { it == MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED } shouldBe true
            val after = stateOf(ready, activation, "none")
            after.status shouldBe "TERMINATED"
            (after.revision - before.revision) shouldBe successes.toLong()
            (after.issues - before.issues) shouldBe successes
            (after.events - before.events) shouldBe successes.toLong()
            (successes in 1..2) shouldBe true
        }
    }
})
