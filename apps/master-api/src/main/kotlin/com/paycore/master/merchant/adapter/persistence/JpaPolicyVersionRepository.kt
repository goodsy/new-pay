package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.PolicyVersionRepository
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersion
import com.paycore.master.merchant.domain.PolicyVersionId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPolicyVersionRepository(private val versions: PolicyVersionJpaRepository, private val entityManager: EntityManager) : PolicyVersionRepository {

    override fun find(id: PolicyVersionId): PolicyVersion? = translate(ENTITY) {
        entityManager.find(JpaPolicyVersionEntity::class.java, id.value)?.let(AuthorizationTermsMapper::toVersion)
    }

    override fun findAll(ids: Collection<PolicyVersionId>): List<PolicyVersion> = translate(ENTITY) {
        if (ids.isEmpty()) emptyList() else versions.findByIds(ids.map { it.value }.toSet()).map(AuthorizationTermsMapper::toVersion)
    }

    override fun listByCode(kind: PolicyKind, code: PolicyCode): List<PolicyVersion> = translate(ENTITY) {
        versions.findByCode(kind.name, code.value).map(AuthorizationTermsMapper::toVersion)
    }

    override fun insert(version: PolicyVersion, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaPolicyVersionEntity.newRow(
                version.id.value, version.kind.name, version.code.value, version.versionNo, version.contentStatus.name, version.contentHash,
                version.reason.value, version.registeredAt, version.registeredBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "policy_version"
    }
}
