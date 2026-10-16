package com.paycore.master.merchant.application

import com.paycore.master.merchant.adapter.persistence.JpaAdminRequestStore
import com.paycore.master.merchant.adapter.persistence.JpaBusinessRepository
import com.paycore.master.merchant.adapter.persistence.JpaMerchantSiteRepository
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MerchantSiteRepository
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.adapter.persistence.JpaMasterChangeLog
import com.paycore.master.merchant.adapter.persistence.JpaMerchantAccountRepository
import com.paycore.master.merchant.adapter.persistence.JpaMerchantRepository
import com.paycore.master.merchant.adapter.persistence.JpaOutboxStore
import com.paycore.master.merchant.application.required.AdminRequestScope
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.AdminReservation
import com.paycore.master.merchant.application.required.AdminReservationOutcome
import com.paycore.master.merchant.application.required.ChangeRecord
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MerchantAccountRepository
import com.paycore.master.merchant.application.required.MerchantRepository
import com.paycore.master.merchant.application.required.OutboxEvent
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.StoredAdminResult
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.FirstDefaultIssuance
import com.paycore.master.merchant.domain.Merchant
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 장애 주입 지점(Port가 SQL을 실행·flush한 **직후**, 같은 TX 안·commit 전). */
enum class MasterFaultPoint {
    ADMIN_RESERVE, ADMIN_COMPLETE, BUSINESS_INSERT, SITE_INSERT, MERCHANT_LOCK, MERCHANT_UPDATE, FIRST_DEFAULT_INSERT, CHANGE_LOG_APPEND,
    OUTBOX_APPEND,

    // MASTER-SNAPSHOT-001
    ACTIVATION_INSERT, ACTIVATION_UPDATE, CONFIRMATION_INSERT, CURRENCY_INSERT, REVIEW_APPEND, ISSUE_RECORD, RELAY_CLAIM, RELAY_MARK,
}

/**
 * 시험 전용 장애 주입기(payment-api FaultInjector와 같은 방식). hook은 예외를 던져 그 지점의 실패를 만들거나(rollback 시험), 다른 연결이 잠금
 * 대기에 들어갈 때까지 TX를 붙잡는다(경합 관찰). 운영 코드에는 없다.
 */
class MasterFaultInjector {
    private val hooks = ConcurrentHashMap<MasterFaultPoint, (Any?) -> Unit>()

    /** 실제 Port가 던진 예외(지점별) — 경합 시험이 "UNIQUE 충돌이 실제로 났다"를 관찰하는 데 쓴다. */
    val failures: MutableList<Pair<MasterFaultPoint, Throwable>> = java.util.concurrent.CopyOnWriteArrayList()

    fun after(point: MasterFaultPoint, hook: (result: Any?) -> Unit) {
        hooks[point] = hook
    }

    fun reset() {
        hooks.clear()
        failures.clear()
    }

    fun <T> around(point: MasterFaultPoint, block: () -> T): T {
        val result = try {
            block()
        } catch (e: RuntimeException) {
            failures += point to e
            throw e
        }
        hooks[point]?.invoke(result)
        return result
    }
}

/**
 * jOOQ 문장 실행 시점의 Spring TX 상태 기록기(시험 전용, review m-2). [recording] 동안만 각 문장의 (실제 TX 있음, 읽기 전용, 격리 수준)을 남긴다.
 */
class JooqStatementRecorder : org.jooq.ExecuteListener {
    data class Observation(val inTransaction: Boolean, val readOnly: Boolean, val isolation: Int?)

    @Volatile
    var recording: Boolean = false
    val observations: MutableList<Observation> = java.util.concurrent.CopyOnWriteArrayList()

    override fun executeStart(ctx: org.jooq.ExecuteContext) {
        if (!recording) return
        observations += Observation(
            org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
            org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
            org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
        )
    }
}

/** 시험 전용 서버 시계(@Primary). 기본값은 [OnboardingFixture.t0]이며 시험이 시각을 옮긴 뒤 [reset]한다. */
class MutableMasterClock : MasterClock {
    private val instant = java.util.concurrent.atomic.AtomicReference(OnboardingFixture.t0)

    override fun now(): Instant = instant.get()

    fun set(at: Instant) = instant.set(at)

    fun reset() = instant.set(OnboardingFixture.t0)
}

/** 시험 전용 ID 생성기(@Primary): 기본은 실제 생성기, [forceNext]로 다음 한 번의 ID를 정해 서버 생성 ID 충돌을 만든다. */
class ScriptedIdGenerator(private val real: RandomMasterIdGenerator) : MasterIdGenerator {
    private val forced = ConcurrentHashMap<String, String>()

    fun forceNext(prefix: String, id: String) {
        forced[prefix] = id
    }

    fun reset() = forced.clear()

    override fun newId(prefix: String): String = forced.remove(prefix) ?: real.newId(prefix)

    override fun newUuid(): String = real.newUuid()
}

/** 실제 JPA Store를 감싸 hook을 거치게 하는 Port 대역(@Primary — MerchantOnboardingService가 이것을 주입받는다). */
@TestConfiguration(proxyBeanMethods = false)
class MasterFaultInjectionConfig {

    /** 시험용 가짜 PAYMENT Inbox와 그 주소(운영 설정의 dispatch.endpoint를 시험 컨텍스트에서만 덮어쓴다). */
    @Bean(destroyMethod = "close")
    fun fakePaymentInbox(): FakePaymentInbox = FakePaymentInbox()

    @Bean
    fun fakePaymentInboxEndpoint(inbox: FakePaymentInbox): org.springframework.test.context.DynamicPropertyRegistrar =
        org.springframework.test.context.DynamicPropertyRegistrar { registry ->
            registry.add("paycore.master.entitlement.dispatch.endpoint") { inbox.endpoint }
        }

    @Bean
    @Primary
    fun faultInjectingActivationStore(
        real: com.paycore.master.merchant.adapter.persistence.JpaPaymentMethodActivationRepository,
        f: MasterFaultInjector,
    ): com.paycore.master.merchant.application.required.PaymentMethodActivationRepository =
        object : com.paycore.master.merchant.application.required.PaymentMethodActivationRepository by real {
            override fun insert(
                activation: com.paycore.master.merchant.domain.PaymentMethodActivation,
                transition: com.paycore.master.merchant.domain.ActivationTransition,
                requestRecordId: String,
            ) = f.around(MasterFaultPoint.ACTIVATION_INSERT) { real.insert(activation, transition, requestRecordId) }

            override fun update(
                activation: com.paycore.master.merchant.domain.PaymentMethodActivation,
                expectedVersion: Long,
                transition: com.paycore.master.merchant.domain.ActivationTransition,
                requestRecordId: String,
            ) = f.around(MasterFaultPoint.ACTIVATION_UPDATE) { real.update(activation, expectedVersion, transition, requestRecordId) }
        }

    @Bean
    @Primary
    fun faultInjectingConfirmationStore(
        real: com.paycore.master.merchant.adapter.persistence.JpaSiteActivationConfirmationRepository,
        f: MasterFaultInjector,
    ): com.paycore.master.merchant.application.required.SiteActivationConfirmationRepository =
        object : com.paycore.master.merchant.application.required.SiteActivationConfirmationRepository by real {
            override fun insert(confirmation: com.paycore.master.merchant.domain.SiteActivationConfirmation, requestRecordId: String) =
                f.around(MasterFaultPoint.CONFIRMATION_INSERT) { real.insert(confirmation, requestRecordId) }
        }

    @Bean
    @Primary
    fun faultInjectingCurrencyStore(
        real: com.paycore.master.merchant.adapter.persistence.JpaCurrencyEnablementRepository,
        f: MasterFaultInjector,
    ): com.paycore.master.merchant.application.required.CurrencyEnablementRepository =
        object : com.paycore.master.merchant.application.required.CurrencyEnablementRepository by real {
            override fun insert(enablement: com.paycore.master.merchant.domain.CurrencyEnablement, requestRecordId: String) =
                f.around(MasterFaultPoint.CURRENCY_INSERT) { real.insert(enablement, requestRecordId) }
        }

    @Bean
    @Primary
    fun faultInjectingReviewStore(
        real: com.paycore.master.merchant.adapter.persistence.JpaSiteReviewStore,
        f: MasterFaultInjector,
    ): com.paycore.master.merchant.application.required.SiteReviewStore =
        object : com.paycore.master.merchant.application.required.SiteReviewStore by real {
            override fun append(decision: com.paycore.master.merchant.domain.SiteReviewDecision, requestRecordId: String) =
                f.around(MasterFaultPoint.REVIEW_APPEND) { real.append(decision, requestRecordId) }
        }

    @Bean
    @Primary
    fun faultInjectingIssueStore(
        real: com.paycore.master.merchant.adapter.persistence.JpaEntitlementIssueStore,
        f: MasterFaultInjector,
    ): com.paycore.master.merchant.application.required.EntitlementIssueStore =
        object : com.paycore.master.merchant.application.required.EntitlementIssueStore by real {
            override fun record(publication: com.paycore.master.merchant.application.required.SnapshotPublication) =
                f.around(MasterFaultPoint.ISSUE_RECORD) { real.record(publication) }
        }

    @Bean
    @Primary
    fun faultInjectingRelayStore(
        real: com.paycore.master.merchant.adapter.persistence.JpaEntitlementRelayStore,
        f: MasterFaultInjector,
    ): com.paycore.master.merchant.application.required.EntitlementRelayStore =
        object : com.paycore.master.merchant.application.required.EntitlementRelayStore by real {
            override fun claim(owner: String, now: Instant, claimUntil: Instant, limit: Int) =
                f.around(MasterFaultPoint.RELAY_CLAIM) { real.claim(owner, now, claimUntil, limit) }

            override fun markPublished(eventId: String, owner: String, ack: com.paycore.master.merchant.application.required.SnapshotAck) =
                f.around(MasterFaultPoint.RELAY_MARK) { real.markPublished(eventId, owner, ack) }
        }

    @Bean
    fun masterFaultInjector(): MasterFaultInjector = MasterFaultInjector()

    @Bean
    @Primary
    fun mutableMasterClock(): MutableMasterClock = MutableMasterClock()

    @Bean
    fun jooqStatementRecorder(): JooqStatementRecorder = JooqStatementRecorder()

    /** Boot jOOQ 자동 구성이 ExecuteListenerProvider bean을 DSLContext에 붙인다(시험 전용 관찰). */
    @Bean
    fun jooqStatementRecorderProvider(recorder: JooqStatementRecorder): org.jooq.ExecuteListenerProvider =
        org.jooq.impl.DefaultExecuteListenerProvider(recorder)

    @Bean
    @Primary
    fun scriptedIdGenerator(real: RandomMasterIdGenerator): ScriptedIdGenerator = ScriptedIdGenerator(real)

    @Bean
    @Primary
    fun faultInjectingBusinessStore(real: JpaBusinessRepository, f: MasterFaultInjector): BusinessRepository = object : BusinessRepository by real {
        override fun insert(business: Business) = f.around(MasterFaultPoint.BUSINESS_INSERT) { real.insert(business) }
    }

    @Bean
    @Primary
    fun faultInjectingMerchantSiteStore(real: JpaMerchantSiteRepository, f: MasterFaultInjector): MerchantSiteRepository =
        object : MerchantSiteRepository by real {
            override fun insert(site: MerchantSite) = f.around(MasterFaultPoint.SITE_INSERT) { real.insert(site) }
        }

    @Bean
    @Primary
    fun faultInjectingAdminRequestStore(real: JpaAdminRequestStore, f: MasterFaultInjector): AdminRequestStore = object : AdminRequestStore {
        override fun reserveOrLock(reservation: AdminReservation): AdminReservationOutcome =
            f.around(MasterFaultPoint.ADMIN_RESERVE) { real.reserveOrLock(reservation) }

        override fun complete(scope: AdminRequestScope, result: StoredAdminResult, at: Instant) =
            f.around(MasterFaultPoint.ADMIN_COMPLETE) { real.complete(scope, result, at) }
    }

    @Bean
    @Primary
    fun faultInjectingMerchantStore(real: JpaMerchantRepository, f: MasterFaultInjector): MerchantRepository = object : MerchantRepository by real {
        override fun lock(id: com.paycore.master.merchant.domain.MerchantId): Versioned<Merchant>? =
            f.around(MasterFaultPoint.MERCHANT_LOCK) { real.lock(id) }

        override fun update(merchant: Merchant, expectedVersion: Long): Long =
            f.around(MasterFaultPoint.MERCHANT_UPDATE) { real.update(merchant, expectedVersion) }
    }

    @Bean
    @Primary
    fun faultInjectingMerchantAccountStore(real: JpaMerchantAccountRepository, f: MasterFaultInjector): MerchantAccountRepository =
        object : MerchantAccountRepository by real {
            override fun insertFirstDefault(issuance: FirstDefaultIssuance) =
                f.around(MasterFaultPoint.FIRST_DEFAULT_INSERT) { real.insertFirstDefault(issuance) }
        }

    @Bean
    @Primary
    fun faultInjectingChangeLog(real: JpaMasterChangeLog, f: MasterFaultInjector): MasterChangeLog = object : MasterChangeLog {
        override fun append(record: ChangeRecord) = f.around(MasterFaultPoint.CHANGE_LOG_APPEND) { real.append(record) }
    }

    @Bean
    @Primary
    fun faultInjectingOutboxStore(real: JpaOutboxStore, f: MasterFaultInjector): OutboxStore = object : OutboxStore {
        override fun append(event: OutboxEvent) = f.around(MasterFaultPoint.OUTBOX_APPEND) { real.append(event) }
    }
}
