package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.BusinessChangeProposalStore
import com.paycore.master.merchant.domain.BusinessChangeDigest
import com.paycore.master.merchant.domain.BusinessChangeProposal
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 제안 INSERT 전용 저장(BIZ-CRIT-APPROVAL-001 §3.1 — JPA 선택은 [JpaBusinessChangeProposalEntity]). 명령 TX 안에서만(MANDATORY). [insert]는 persist + flush(PK·FK 위반이 Port 안에서
 * 드러난다). [find]는 같은 TX의 영속성 컨텍스트를 거치지 않고 DB 행을 읽는다(제안은 불변이라 관리 Entity 재사용 문제는 없지만 실행 TX에서 처음 읽는 값 — `em.find` 한 번).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBusinessChangeProposalStore(private val entityManager: EntityManager) : BusinessChangeProposalStore {
    override fun insert(proposal: BusinessChangeProposal, digest: BusinessChangeDigest, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaBusinessChangeProposalEntity.newRow(
                proposal.id.value, proposal.businessId.value, proposal.baseVersion, proposal.contentJson(), digest.value,
                BusinessChangeProposalMapper.changesJson(proposal.changes), BusinessChangeProposalMapper.fieldsJson(proposal.importantFields), proposal.representativeChanged,
                proposal.subjectCorrection, proposal.reason.value, proposal.evidenceRef.value, proposal.requestedBy.value, proposal.requestedAt, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    override fun find(id: BusinessChangeProposalId): BusinessChangeProposal? = translate(ENTITY) {
        entityManager.find(JpaBusinessChangeProposalEntity::class.java, id.value)?.let(BusinessChangeProposalMapper::toDomain)
    }

    private companion object {
        const val ENTITY = "BusinessChangeProposal"
    }
}
