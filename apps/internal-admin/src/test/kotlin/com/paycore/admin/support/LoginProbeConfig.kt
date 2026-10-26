package com.paycore.admin.support

import com.paycore.admin.account.adapter.integration.BCryptPasswordHasher
import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.domain.PasswordHash
import com.paycore.admin.audit.adapter.persistence.JpaAdminAuditStore
import com.paycore.admin.audit.application.required.AdminAuditStore
import com.paycore.admin.audit.domain.AdminAuditEvent
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 로그인 경로 동등성 계측(시험 전용 — `LoginPathEquivalenceSpec`·`LoginCredentialChangeRaceSpec`): BCrypt 비교 횟수·새 로컬 TX 시작 횟수·감사 append 횟수를 센다.
 * [ProbePasswordHasher.duringMatch]는 비교(§4.3.2 (a))와 잠금 TX((b)) 사이에 다른 연결의 커밋을 끼워 넣는 고리다.
 */
@TestConfiguration(proxyBeanMethods = false)
class LoginProbeConfig {
    @Bean
    @Primary
    fun probePasswordHasher(): ProbePasswordHasher = ProbePasswordHasher(BCryptPasswordHasher(COST))

    @Bean
    @Primary
    fun countingAuditStore(delegate: JpaAdminAuditStore): CountingAuditStore = CountingAuditStore(delegate)

    companion object {
        const val COST = 4

        @JvmStatic
        @Bean
        fun countingTransactionManagerPostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (beanName == "transactionManager" && bean is PlatformTransactionManager) CountingTransactionManager(bean) else bean
        }
    }
}

class ProbePasswordHasher(private val delegate: BCryptPasswordHasher) : PasswordHasher {
    val matchCalls = AtomicInteger()
    val duringMatch = AtomicReference<(() -> Unit)?>(null)

    override fun hash(raw: CharArray): PasswordHash = delegate.hash(raw)

    override fun matches(raw: CharArray, hash: PasswordHash?): Boolean {
        matchCalls.incrementAndGet()
        val result = delegate.matches(raw, hash)
        duringMatch.getAndSet(null)?.invoke()
        return result
    }
}

class CountingAuditStore(private val delegate: JpaAdminAuditStore) : AdminAuditStore {
    val appends = AtomicInteger()
    val last = AtomicReference<AdminAuditEvent?>(null)

    override fun append(event: AdminAuditEvent) {
        appends.incrementAndGet()
        last.set(event)
        delegate.append(event)
    }
}

class CountingTransactionManager(private val delegate: PlatformTransactionManager) : PlatformTransactionManager {
    val begins = AtomicInteger()

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
        delegate.getTransaction(definition).also { if (it.isNewTransaction) begins.incrementAndGet() }

    override fun commit(status: TransactionStatus) = delegate.commit(status)

    override fun rollback(status: TransactionStatus) = delegate.rollback(status)
}
