package com.paycore.simulator.authentication.adapter.persistence

import com.paycore.simulator.authentication.application.required.LookupLogStore
import com.paycore.simulator.authentication.domain.LookupBehaviour
import com.paycore.simulator.authentication.domain.ResultRef
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** [LookupLogStore] JPA 구현(append-only). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaLookupLogStore(
    private val rows: LookupLogJpaRepository,
    private val entityManager: EntityManager,
) : LookupLogStore {
    override fun countFor(resultRef: ResultRef): Long = rows.countByResultRef(resultRef.value)

    override fun append(resultRef: ResultRef, at: Instant, behaviour: LookupBehaviour) {
        entityManager.persist(JpaLookupLogEntity.newRow(resultRef.value, at, behaviour.name))
        entityManager.flush()
    }
}
