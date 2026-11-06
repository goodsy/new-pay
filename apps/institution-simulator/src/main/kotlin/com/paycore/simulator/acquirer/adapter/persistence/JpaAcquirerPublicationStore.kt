package com.paycore.simulator.acquirer.adapter.persistence

import com.paycore.simulator.acquirer.application.required.AcquirerPublicationStore
import com.paycore.simulator.acquirer.application.required.NewPublication
import com.paycore.simulator.acquirer.application.required.PendingPublication
import com.paycore.simulator.acquirer.application.required.PublicationKind
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [AcquirerPublicationStore] JPA 구현(MANDATORY TX). 진행 표는 변경 감지 + `@Version`(바뀌는 열 = state·published_at·updated_at·version — 열 단위 권한과 같다),
 * 게시 기록은 `@Immutable` persist + flush. 삭제 연산 없음.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquirerPublicationStore(private val entityManager: EntityManager) : AcquirerPublicationStore {

    override fun schedule(items: List<NewPublication>, at: Instant) {
        if (items.isEmpty()) return
        AcquirerPersistenceFailures.flushing(entityManager) {
            items.forEach { i -> entityManager.persist(JpaAcquirerWorkItemEntity.newRow(i.receiptId, i.acquirerCode, i.kind.name, i.seq, i.fileName, i.dueAt, at)) }
        }
    }

    override fun due(now: Instant, limit: Int, excluding: Set<Long>): List<PendingPublication> {
        val skip = if (excluding.isEmpty()) "" else " and w.workItemId not in :excluded"
        val query = entityManager.createQuery(
            "select w from JpaAcquirerWorkItemEntity w where w.state = :pending and w.dueAt <= :now$skip order by w.dueAt, w.workItemId",
            JpaAcquirerWorkItemEntity::class.java,
        ).setParameter("pending", JpaAcquirerWorkItemEntity.PENDING).setParameter("now", now).setMaxResults(limit)
        if (excluding.isNotEmpty()) query.setParameter("excluded", excluding)
        return query.resultList.map { w ->
            PendingPublication(checkNotNull(w.workItemId), w.receiptId, w.acquirerCode, PublicationKind.valueOf(w.kind), w.seq, w.fileName, w.dueAt, w.version)
        }
    }

    override fun markPublished(publication: PendingPublication, contentSha256: String, contentBytes: Int, at: Instant): Boolean {
        val row = entityManager.find(JpaAcquirerWorkItemEntity::class.java, publication.workItemId) ?: return false
        // 같은 TX에서 다시 읽은 행이 호출자가 본 version·상태와 다르면(다른 Worker가 먼저 끝냄) 아무것도 바꾸지 않는다 — stale 값을 덮어쓰지 않는다.
        if (row.version != publication.version || row.state != JpaAcquirerWorkItemEntity.PENDING) return false
        AcquirerPersistenceFailures.flushing(entityManager) {
            row.markPublished(at)
            entityManager.persist(
                JpaAcquirerOutboundFileEntity.newRow(publication.acquirerCode, publication.fileName, publication.workItemId, contentSha256, contentBytes, at),
            )
        }
        return true
    }
}
