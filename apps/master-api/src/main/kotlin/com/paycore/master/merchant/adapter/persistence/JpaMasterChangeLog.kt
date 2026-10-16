package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.ChangeRecord
import com.paycore.master.merchant.application.required.MasterChangeLog
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMasterChangeLog(private val entityManager: EntityManager) : MasterChangeLog {

    override fun append(record: ChangeRecord): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaMasterChangeHistoryEntity.newRow(
                record.requestRecordId, record.operation.code, record.aggregateType, record.aggregateId, record.changeType, record.actor.value,
                record.occurredAt, MasterJson.serialize(record.detail),
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "master_change_history"
    }
}
