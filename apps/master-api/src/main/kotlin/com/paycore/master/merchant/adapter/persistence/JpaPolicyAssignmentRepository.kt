package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.PolicyAssignmentRepository
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PolicyAssignment
import com.paycore.master.merchant.domain.PolicyAssignmentId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPolicyAssignmentRepository(private val assignments: PolicyAssignmentJpaRepository, private val entityManager: EntityManager) : PolicyAssignmentRepository {

    override fun find(id: PolicyAssignmentId): Versioned<PolicyAssignment>? = translate(ENTITY) {
        entityManager.find(JpaPolicyAssignmentEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: PolicyAssignmentId): Versioned<PolicyAssignment>? = translate(ENTITY) {
        entityManager.lockRow(JpaPolicyAssignmentEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByAccount(accountId: MerchantAccountId): List<PolicyAssignment> = translate(ENTITY) {
        assignments.findByAccount(accountId.value).map(AuthorizationTermsMapper::toAssignment)
    }

    override fun insert(assignment: PolicyAssignment, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaPolicyAssignmentEntity.newRow(
                assignment.id.value, assignment.merchantId.value, assignment.agreementId.value, assignment.merchantAccountId.value,
                assignment.currency.value, assignment.kind.name, assignment.policyVersionId.value, assignment.period.from, assignment.period.to,
                assignment.reason.value, assignment.createdAt, assignment.createdBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    override fun update(assignment: PolicyAssignment, expectedVersion: Long): Unit = translate(ENTITY) {
        val row = entityManager.find(JpaPolicyAssignmentEntity::class.java, assignment.id.value) ?: error("assignment is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.effectiveFrom == assignment.period.from && row.policyVersionId == assignment.policyVersionId.value) { "only the end can change" }
        row.effectiveTo = assignment.period.to
        row.endedAt = assignment.endedAt
        row.endedBy = assignment.endedBy?.value
        entityManager.flush()
    }

    private fun versioned(row: JpaPolicyAssignmentEntity) = Versioned(AuthorizationTermsMapper.toAssignment(row), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "policy_assignment"
    }
}
