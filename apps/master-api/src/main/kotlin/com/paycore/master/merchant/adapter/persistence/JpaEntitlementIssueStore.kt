package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.EntitlementSnapshotPublisher
import com.paycore.master.merchant.application.required.EntitlementIssueStore
import com.paycore.master.merchant.application.required.SnapshotIssueRecord
import com.paycore.master.merchant.application.required.SnapshotPublication
import com.paycore.master.merchant.domain.MerchantId
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 발행 기록 + Outbox(`ENTITLEMENT_SNAPSHOT`)를 같은 TX에 쓴다. Outbox payload = 정규 JSON 전송 payload(문자열 그대로, JSON 컬럼). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEntitlementIssueStore(private val issues: SnapshotIssueJpaRepository, private val entityManager: EntityManager) : EntitlementIssueStore {

    override fun latest(merchantId: MerchantId): SnapshotIssueRecord? = translate(ENTITY) {
        issues.findLatestFirst(merchantId.value, PageRequest.of(0, 1)).firstOrNull()?.let(EntitlementPersistenceMapper::toIssue)
    }

    override fun record(publication: SnapshotPublication): Unit = translate(ENTITY) {
        val issue = publication.issue
        require(issue.schemaVersion == publication.schemaVersion) { "the issue record and the outbox event carry the same schema version" }
        entityManager.persist(
            JpaOutboxEventEntity.pending(
                eventId = issue.eventId,
                effectKey = "${publication.eventType}:${issue.merchantId.value}:${issue.revision}:${issue.leaseSeq}",
                eventType = publication.eventType,
                schemaVersion = publication.schemaVersion,
                eventCategory = EntitlementSnapshotPublisher.EVENT_CATEGORY,
                producer = publication.producer,
                aggregateType = AGGREGATE_TYPE,
                aggregateId = issue.merchantId.value,
                aggregateVersion = issue.revision,
                occurredAt = issue.issuedAt,
                correlationId = publication.correlationId,
                causationId = publication.causationId,
                payload = publication.wirePayload,
                priority = issue.priority.value,
            ),
        )
        entityManager.flush()
        entityManager.persist(
            JpaSnapshotIssueEntity.newRow(
                SnapshotIssueKey.of(issue.merchantId.value, issue.revision, issue.leaseSeq), issue.contentHash, issue.issuedAt, issue.leaseUntil,
                issue.priority.name, issue.eventId, publication.schemaVersion,
            ),
        )
        entityManager.flush()
    }

    override fun merchantsDueForRenewal(threshold: Instant, limit: Int): List<MerchantId> = translate(ENTITY) {
        issues.merchantsWithLeaseBefore(threshold, PageRequest.of(0, limit)).map(::MerchantId)
    }

    private companion object {
        const val ENTITY = "entitlement_snapshot_issue"
        const val AGGREGATE_TYPE = "Merchant"
    }
}
