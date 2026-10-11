package com.paycore.master.merchant.application

import com.paycore.master.merchant.adapter.persistence.JpaAdminRequestStore
import com.paycore.master.merchant.adapter.persistence.JpaBusinessStore
import com.paycore.master.merchant.adapter.persistence.JpaMerchantSiteStore
import com.paycore.master.merchant.application.required.BusinessStore
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MerchantSiteStore
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.adapter.persistence.JpaMasterChangeLog
import com.paycore.master.merchant.adapter.persistence.JpaMerchantAccountStore
import com.paycore.master.merchant.adapter.persistence.JpaMerchantStore
import com.paycore.master.merchant.adapter.persistence.JpaOutboxStore
import com.paycore.master.merchant.application.required.AdminRequestScope
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.AdminReservation
import com.paycore.master.merchant.application.required.AdminReservationOutcome
import com.paycore.master.merchant.application.required.ChangeRecord
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MerchantAccountStore
import com.paycore.master.merchant.application.required.MerchantStore
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
    fun faultInjectingBusinessStore(real: JpaBusinessStore, f: MasterFaultInjector): BusinessStore = object : BusinessStore by real {
        override fun insert(business: Business) = f.around(MasterFaultPoint.BUSINESS_INSERT) { real.insert(business) }
    }

    @Bean
    @Primary
    fun faultInjectingMerchantSiteStore(real: JpaMerchantSiteStore, f: MasterFaultInjector): MerchantSiteStore =
        object : MerchantSiteStore by real {
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
    fun faultInjectingMerchantStore(real: JpaMerchantStore, f: MasterFaultInjector): MerchantStore = object : MerchantStore by real {
        override fun lock(id: com.paycore.master.merchant.domain.MerchantId): Versioned<Merchant>? =
            f.around(MasterFaultPoint.MERCHANT_LOCK) { real.lock(id) }

        override fun update(merchant: Merchant, expectedVersion: Long): Long =
            f.around(MasterFaultPoint.MERCHANT_UPDATE) { real.update(merchant, expectedVersion) }
    }

    @Bean
    @Primary
    fun faultInjectingMerchantAccountStore(real: JpaMerchantAccountStore, f: MasterFaultInjector): MerchantAccountStore =
        object : MerchantAccountStore by real {
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
