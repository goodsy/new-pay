package com.paycore.simulator.authentication.adapter.persistence

import com.paycore.simulator.authentication.application.required.AuthenticationTransactionRepository
import com.paycore.simulator.authentication.application.required.DuplicateRegistration
import com.paycore.simulator.authentication.domain.AuthenticationTransaction
import com.paycore.simulator.authentication.domain.PlanEcho
import com.paycore.simulator.authentication.domain.RegistrationRequest
import com.paycore.simulator.authentication.domain.ResultRef
import com.paycore.simulator.authentication.domain.TransactionId
import com.paycore.simulator.authentication.domain.TransactionStatus
import com.paycore.simulator.authentication.domain.VirtualCard
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException

/** [AuthenticationTransactionRepository] JPA 구현(MANDATORY TX, persist만, 끝 flush, 잠가 읽은 Entity 변경 감지 — PayCore 관례). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAuthenticationTransactionRepository(
    private val rows: AuthenticationTransactionJpaRepository,
    private val entityManager: EntityManager,
) : AuthenticationTransactionRepository {

    override fun insert(transaction: AuthenticationTransaction) {
        val r = transaction.request
        try {
            entityManager.persist(
                JpaAuthenticationTransactionEntity.newRow(
                    transaction.id.value, transaction.clientId, r.state, r.paymentId, r.merchantRef, r.environment, r.amountMinor, r.currency, r.plan.kind,
                    r.plan.months, r.cardCompany, r.returnUrl, transaction.createdAt, transaction.expiresAt,
                ),
            )
            entityManager.flush()
        } catch (e: RuntimeException) {
            if (isDuplicate(e)) throw DuplicateRegistration()
            throw e
        }
    }

    override fun findByClientAndState(clientId: String, state: String): AuthenticationTransaction? = rows.findByClientIdAndState(clientId, state)?.let(::toDomain)

    override fun findByResultRef(resultRef: ResultRef): AuthenticationTransaction? = rows.findByResultRef(resultRef.value)?.let(::toDomain)

    override fun findByEvidenceRef(evidenceRef: String): List<AuthenticationTransaction> = rows.findAllByEvidenceRef(evidenceRef).map(::toDomain)

    override fun find(id: TransactionId): AuthenticationTransaction? = rows.findById(id.value).orElse(null)?.let(::toDomain)

    override fun lock(id: TransactionId): AuthenticationTransaction? = rows.lockById(id.value)?.let(::toDomain)

    override fun update(transaction: AuthenticationTransaction) {
        val row = entityManager.find(JpaAuthenticationTransactionEntity::class.java, transaction.id.value) ?: error("transaction is not stored")
        check(row.status == TransactionStatus.REGISTERED.name) { "a result is fixed once" }
        row.status = transaction.status.name
        row.cardRef = transaction.card?.ref
        row.resultRef = transaction.resultRef?.value
        row.evidenceRef = transaction.evidenceRef
        row.completedAt = transaction.completedAt
        row.validUntil = transaction.validUntil
        entityManager.flush()
    }

    private fun toDomain(row: JpaAuthenticationTransactionEntity) = AuthenticationTransaction.reconstitute(
        id = TransactionId(row.transactionId),
        clientId = row.clientId,
        request = RegistrationRequest(
            row.paymentId, row.merchantRef, row.environment, row.amountMinor, row.currency, PlanEcho(row.planKind, row.installmentMonths), row.cardCompany,
            row.state, row.returnUrl,
        ),
        createdAt = row.createdAt,
        expiresAt = row.expiresAt,
        status = TransactionStatus.valueOf(row.status),
        card = row.cardRef?.let { checkNotNull(VirtualCard.fromRef(it)) { "stored card reference is unknown" } },
        resultRef = row.resultRef?.let(::ResultRef),
        evidenceRef = row.evidenceRef,
        completedAt = row.completedAt,
        validUntil = row.validUntil,
    )

    /** MySQL 1062(중복 키) — (client, state)·결과 참조 UNIQUE 경합. */
    private fun isDuplicate(e: Throwable): Boolean =
        generateSequence(e) { cause -> cause.cause?.takeIf { it !== cause } }.take(MAX_CAUSE_DEPTH).filterIsInstance<SQLException>().any { it.errorCode == MYSQL_DUPLICATE_KEY }

    private companion object {
        const val MAX_CAUSE_DEPTH = 20
        const val MYSQL_DUPLICATE_KEY = 1062
    }
}
