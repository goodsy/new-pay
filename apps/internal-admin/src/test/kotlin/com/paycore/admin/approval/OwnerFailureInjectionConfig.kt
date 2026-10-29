package com.paycore.admin.approval

import com.paycore.admin.approval.adapter.persistence.JpaApprovalRequestRepository
import com.paycore.admin.approval.adapter.persistence.JpaOwnerExecutionRepository
import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.VersionedApproval
import com.paycore.admin.approval.application.required.VersionedOwnerExecution
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.OwnerExecution
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 소유 서비스 승인 실패 주입(시험 전용 — 실제 JPA 저장 Port·TX 관리자에 위임하고, 켜 둔 동안만 실패를 던진다. 실패 위치 = 실제 호출 지점):
 * - [InjectingApprovalRepository.deadlockOnInsert]: 승인 행 INSERT에서 MySQL 1213과 같은 분류 — 요청 TX 전체 rollback → 409 RETRY_LATER(L-9).
 * - [InjectingExecutionRepository.failExecutionUpdate]: 실행 행 갱신(TX2)에서 저장 실패 — TX2 rollback → 승인 EXECUTING 유지(§4.13 D).
 * - [CommitUnknownTransactionManager.mode]: **실행 행을 INSERT한 TX(= 승인 TX1)의 commit**에서 결과 불명확을 흉내 낸다(I-89): `COMMITTED_THEN_LOST`는 실제로 커밋한 뒤
 *   예외(응답 유실), `NOT_COMMITTED`는 rollback한 뒤 예외(연결 끊김으로 커밋되지 않음). 1회 쓰면 꺼진다.
 */
@TestConfiguration(proxyBeanMethods = false)
class OwnerFailureInjectionConfig {
    @Bean
    @Primary
    fun injectingApprovalRepository(delegate: JpaApprovalRequestRepository): InjectingApprovalRepository = InjectingApprovalRepository(delegate)

    @Bean
    @Primary
    fun injectingExecutionRepository(delegate: JpaOwnerExecutionRepository): InjectingExecutionRepository = InjectingExecutionRepository(delegate)

    companion object {
        @Bean
        @JvmStatic
        fun commitUnknownTransactionManagerPostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (beanName == "transactionManager" && bean is PlatformTransactionManager) CommitUnknownTransactionManager(bean) else bean
        }
    }
}

class InjectingApprovalRepository(private val delegate: JpaApprovalRequestRepository) : ApprovalRequestRepository by delegate {
    val deadlockOnInsert = AtomicBoolean(false)

    override fun insert(request: ApprovalRequest): VersionedApproval {
        val inserted = delegate.insert(request)
        if (deadlockOnInsert.get()) throw ApprovalPersistenceFailure.Deadlock(java.sql.SQLException("Deadlock found when trying to get lock (injected)", "40001", 1213))
        return inserted
    }
}

class InjectingExecutionRepository(private val delegate: JpaOwnerExecutionRepository) : OwnerExecutionRepository by delegate {
    val failExecutionUpdate = AtomicBoolean(false)

    override fun insert(execution: OwnerExecution): VersionedOwnerExecution =
        delegate.insert(execution).also { CommitUnknownTransactionManager.executionInserted.set(true) }

    override fun update(current: VersionedOwnerExecution, next: OwnerExecution): VersionedOwnerExecution {
        if (failExecutionUpdate.get()) throw IllegalStateException("injected TX2 storage failure")
        return delegate.update(current, next)
    }
}

class CommitUnknownTransactionManager(private val delegate: PlatformTransactionManager) : PlatformTransactionManager {
    enum class Mode { OFF, COMMITTED_THEN_LOST, NOT_COMMITTED }

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
        delegate.getTransaction(definition).also { if (it.isNewTransaction) executionInserted.set(false) }

    override fun commit(status: TransactionStatus) {
        val armed = executionInserted.get() && status.isNewTransaction && mode.get() != Mode.OFF
        if (!armed) return delegate.commit(status)
        val current = mode.getAndSet(Mode.OFF)
        executionInserted.set(false)
        if (current == Mode.COMMITTED_THEN_LOST) delegate.commit(status) else delegate.rollback(status)
        throw TransactionSystemException("injected: connection lost during commit ($current)")
    }

    override fun rollback(status: TransactionStatus) = delegate.rollback(status)

    companion object {
        val mode = AtomicReference(Mode.OFF)
        val executionInserted: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    }
}
