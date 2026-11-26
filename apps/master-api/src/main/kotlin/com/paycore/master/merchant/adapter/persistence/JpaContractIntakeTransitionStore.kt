package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.ContractIntakeTransitionStore
import com.paycore.master.merchant.domain.ContractIntakeTransition
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 접수 건 전이 이력 INSERT 전용 저장(MERCHANT-FLOW-001 §4A.14 M-2). 순번 = 그 접수 건의 마지막 순번 + 1(사업자 행 잠금 아래 — 같은 사업자 명령은 직렬화된다).
 * PK 중복(잠금 우회·결함)은 flush에서 [com.paycore.master.merchant.application.required.StoreConstraint.CONTRACT_INTAKE_ROW]로 드러난다. 새 행은 persist만.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaContractIntakeTransitionStore(
    private val transitions: ContractIntakeTransitionJpaRepository,
    private val entityManager: EntityManager,
) : ContractIntakeTransitionStore {

    override fun append(transition: ContractIntakeTransition): Int = translate(ENTITY) {
        val businessId = transition.businessId.value
        val seq = transitions.lastSeq(businessId, transition.intakeSeq) + 1
        check((seq == 1) == (transition.from == null)) { "the creation row is transition 1 and only it" }
        val row = JpaContractIntakeTransitionEntity.newRow(
            businessId, transition.intakeSeq, seq, transition.from?.name, transition.to.name, transition.migrated, transition.reason?.value, transition.document?.value,
            transition.at, transition.by.value,
        )
        entityManager.persist(row)
        entityManager.flush()
        seq
    }

    private companion object {
        const val ENTITY = "ContractIntakeTransition"
    }
}
