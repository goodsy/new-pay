package com.paycore.payment.entitlement

import com.paycore.payment.PaymentMySqlContainerConfig
import com.paycore.payment.entitlement.adapter.persistence.JpaEntitlementProjectionStore
import com.paycore.payment.entitlement.application.required.EntitlementClock
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.application.required.InboxRecord
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.EntitlementHead
import com.paycore.payment.payment.adapter.persistence.PaymentDbTestConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 권리 투영·Inbox·운영 eligibility 구현의 실제 MySQL 시험 구성(MASTER-SNAPSHOT-001, CP-DB-001 review2 L-11).
 *
 * - **EligibilityTestConfig를 넣지 않는다**: CreatePaymentService가 운영 Port 구현(EntitlementEligibilityAdapter → entitlement 판정)으로 기동한다.
 * - Flyway는 **운영 위치만**(application.yml: 업무 schema V1·V3 + runtime 권한 V2·V4) 적용하고 앱은 runtime 계정(표 단위 권한)으로 붙는다.
 *   PaymentApiContextSmokeSpec도 이 구성을 써서 같은 컨텍스트·컨테이너를 공유한다.
 * - [EntitlementTestConfig]: 시험 시계(@Primary)와 투영 Store 장애 주입 대역(@Primary, hook 없으면 실제 Store 그대로). 운영 코드에는 없다.
 * - `innodb_lock_wait_timeout` 8초·pool 16은 경합 시험용 **시험 전용** 값이다(PaymentDbTest와 같은 이유).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@Import(PaymentMySqlContainerConfig::class, PaymentDbTestConfig::class, EntitlementTestConfig::class)
@TestPropertySource(
    properties = [
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 8",
        "spring.datasource.hikari.maximum-pool-size=16",
    ],
)
annotation class EntitlementDbTest

/** 투영 Store 장애 주입 지점(Port가 SQL을 실행·flush한 **직후**, 같은 TX 안·commit 전). */
enum class ProjectionFaultPoint { HEAD_LOCK, HEAD_SHARE, INBOX_INSERT, SNAPSHOT_INSERT, MID_OWNER, HEAD_UPDATE }

class ProjectionFaultInjector {
    private val hooks = ConcurrentHashMap<ProjectionFaultPoint, (Any?) -> Unit>()

    fun after(point: ProjectionFaultPoint, hook: (Any?) -> Unit) {
        hooks[point] = hook
    }

    fun reset() = hooks.clear()

    fun <T> around(point: ProjectionFaultPoint, block: () -> T): T = block().also { hooks[point]?.invoke(it) }
}

/** 시험 시계(@Primary). 기본은 [default], 시험이 옮긴 뒤 [reset]한다. */
class MutableEntitlementClock(private val default: Instant) : EntitlementClock {
    private val instant = AtomicReference(default)

    override fun now(): Instant = instant.get()

    fun set(at: Instant) = instant.set(at)

    fun reset() = instant.set(default)
}

@TestConfiguration(proxyBeanMethods = false)
class EntitlementTestConfig {

    @Bean
    @Primary
    fun mutableEntitlementClock(): MutableEntitlementClock = MutableEntitlementClock(EntitlementEnvelopes.T0)

    @Bean
    fun projectionFaultInjector(): ProjectionFaultInjector = ProjectionFaultInjector()

    @Bean
    @Primary
    fun faultInjectingProjectionStore(real: JpaEntitlementProjectionStore, f: ProjectionFaultInjector): EntitlementProjectionStore =
        object : EntitlementProjectionStore by real {
            override fun lockHeadForUpdate(merchantId: String): EntitlementHead =
                f.around(ProjectionFaultPoint.HEAD_LOCK) { real.lockHeadForUpdate(merchantId) }

            override fun lockHeadForShare(merchantId: String): EntitlementHead? =
                f.around(ProjectionFaultPoint.HEAD_SHARE) { real.lockHeadForShare(merchantId) }

            override fun insertInbox(record: InboxRecord) = f.around(ProjectionFaultPoint.INBOX_INSERT) { real.insertInbox(record) }

            override fun insertSnapshot(content: EntitlementContent, schemaVersion: Int) =
                f.around(ProjectionFaultPoint.SNAPSHOT_INSERT) { real.insertSnapshot(content, schemaVersion) }

            override fun recordMidOwners(merchantId: String, mids: Collection<String>, at: Instant) =
                f.around(ProjectionFaultPoint.MID_OWNER) { real.recordMidOwners(merchantId, mids, at) }

            override fun updateHead(head: EntitlementHead, appliedAt: Instant) =
                f.around(ProjectionFaultPoint.HEAD_UPDATE) { real.updateHead(head, appliedAt) }
        }
}
