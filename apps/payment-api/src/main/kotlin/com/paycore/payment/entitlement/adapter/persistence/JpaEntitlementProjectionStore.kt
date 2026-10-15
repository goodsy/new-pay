package com.paycore.payment.entitlement.adapter.persistence

import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.application.required.InboxRecord
import com.paycore.payment.entitlement.application.required.StoredInbox
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.EntitlementHead
import com.paycore.payment.entitlement.domain.HeadBlock
import com.paycore.payment.entitlement.domain.InboxOutcome
import com.paycore.payment.entitlement.domain.JsonValue
import com.paycore.payment.entitlement.domain.Lease
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.time.Instant

interface InboxEventJpaRepository : JpaRepository<JpaInboxEventEntity, Long> {
    @Query("select i from JpaInboxEventEntity i where i.consumer = :consumer and i.eventId = :eventId")
    fun findByEvent(@Param("consumer") consumer: String, @Param("eventId") eventId: String): JpaInboxEventEntity?
}

interface EntitlementHeadJpaRepository : JpaRepository<JpaEntitlementHeadEntity, String> {
    /** 빈 head 자리를 만든다(있으면 바꾸지 않고 그 행의 잠금을 기다린다 — 최초 경쟁을 PK로 직렬화, 관례 §9 IODKU). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT INTO entitlement_head (merchant_id, revision, version) VALUES (:merchantId, 0, 0) ON DUPLICATE KEY UPDATE version = version",
    )
    fun insertEmptyIfAbsent(@Param("merchantId") merchantId: String): Int
}

interface EntitlementSnapshotJpaRepository : JpaRepository<JpaEntitlementSnapshotEntity, SnapshotKey>

interface MidOwnerJpaRepository : JpaRepository<JpaMidOwnerEntity, String>

/**
 * 권리 투영 저장 Port 구현(JPA, payment 모듈 Store와 같은 관례: MANDATORY TX, 쓰기 끝 flush, append-only는 persist만).
 * - head 잠금: `EntityManager.find` 뒤 `refresh(PESSIMISTIC_WRITE)`(= `FOR UPDATE`, Inbox) / `refresh(PESSIMISTIC_READ)`(= MySQL 8 `FOR SHARE`,
 *   CreatePayment 판정 — 진행 중 Inbox 적용과 직렬화). refresh라서 영속성 컨텍스트에 있던 Entity도 최신 커밋 값으로 다시 읽는다.
 * - revision별 내용: 정규 JSON 문자열을 JSON 컬럼에 저장하고, 읽을 때 domain이 구조를 다시 해석하고 contentHash를 다시 계산해 저장 hash와 비교한다
 *   (MySQL JSON 컬럼은 키 순서를 바꿔 저장하므로 문자열이 아니라 내용으로 비교한다).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEntitlementProjectionStore(
    private val heads: EntitlementHeadJpaRepository,
    private val inbox: InboxEventJpaRepository,
    private val owners: MidOwnerJpaRepository,
    private val entityManager: EntityManager,
) : EntitlementProjectionStore {

    private val mapper: JsonMapper = JsonMapper.builder().build()

    override fun lockHeadForUpdate(merchantId: String): EntitlementHead = translate("entitlement_head") {
        heads.insertEmptyIfAbsent(merchantId)
        val row = checkNotNull(entityManager.find(JpaEntitlementHeadEntity::class.java, merchantId)) { "head must exist after insert-if-absent" }
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE)
        toHead(row)
    }

    override fun lockHeadForShare(merchantId: String): EntitlementHead? = translate("entitlement_head") {
        val row = entityManager.find(JpaEntitlementHeadEntity::class.java, merchantId) ?: return@translate null
        entityManager.refresh(row, LockModeType.PESSIMISTIC_READ)
        toHead(row)
    }

    override fun findInbox(consumer: String, eventId: String): StoredInbox? = translate("inbox_event") {
        inbox.findByEvent(consumer, eventId)?.let { StoredInbox(InboxOutcome.valueOf(it.outcome), it.payloadSha256) }
    }

    override fun insertInbox(record: InboxRecord): Unit = translate("inbox_event") {
        require(record.outcome != InboxOutcome.DUPLICATE) { "a duplicate is answered from the stored record, never stored" }
        entityManager.persist(
            JpaInboxEventEntity.newRow(
                record.consumer, record.eventId, record.eventType, record.schemaVersion, record.aggregateId, record.aggregateVersion,
                record.outcome.name, record.headRevisionAfter, record.payload, record.payloadSha256, record.receivedAt,
            ),
        )
        entityManager.flush()
    }

    override fun insertSnapshot(content: EntitlementContent, schemaVersion: Int): Unit = translate("entitlement_snapshot") {
        entityManager.persist(
            JpaEntitlementSnapshotEntity.newRow(SnapshotKey.of(content.merchantId, content.revision), schemaVersion, content.contentHash, content.canonical),
        )
        entityManager.flush()
    }

    override fun findSnapshot(merchantId: String, revision: Long): EntitlementContent? = translate("entitlement_snapshot") {
        val row = entityManager.find(JpaEntitlementSnapshotEntity::class.java, SnapshotKey.of(merchantId, revision)) ?: return@translate null
        val content = EntitlementContent.fromJson(JacksonTree.toJsonValue(mapper.readTree(row.payload)), row.schemaVersion, allowLeaseFields = false)
        check(content.merchantId == merchantId && content.revision == revision) { "stored snapshot must be the requested revision" }
        check(content.contentHash == row.contentHash) { "stored snapshot content does not match its content hash" }
        content
    }

    override fun recordMidOwners(merchantId: String, mids: Collection<String>, at: Instant): Unit = translate("entitlement_mid_owner") {
        mids.distinct().forEach { mid ->
            val existing = owners.findById(mid).orElse(null)
            if (existing == null) {
                entityManager.persist(JpaMidOwnerEntity.newRow(mid, merchantId, at))
            } else {
                check(existing.merchantId == merchantId) { "a MID never moves to another merchant" }
            }
        }
        entityManager.flush()
    }

    override fun findMidOwner(mid: String): String? = translate("entitlement_mid_owner") { owners.findById(mid).orElse(null)?.merchantId }

    override fun updateHead(head: EntitlementHead, appliedAt: Instant): Unit = translate("entitlement_head") {
        val row = checkNotNull(entityManager.find(JpaEntitlementHeadEntity::class.java, head.merchantId)) { "head must be locked first" }
        check(head.revision >= row.revision) { "head revision never decreases" }
        val lease = head.lease
        if (head.revision != row.revision) row.appliedAt = appliedAt
        row.revision = head.revision
        row.contentHash = head.contentHash
        row.schemaVersion = head.schemaVersion
        row.issuedAt = lease?.issuedAt
        row.leaseSeq = lease?.leaseSeq
        row.leaseUntil = lease?.leaseUntil
        row.sourceEventId = head.sourceEventId
        row.blockedReason = head.blocked?.name
        row.blockedRevision = head.blockedRevision
        entityManager.flush()
    }

    private fun toHead(row: JpaEntitlementHeadEntity): EntitlementHead = EntitlementHead(
        merchantId = row.merchantId,
        revision = row.revision,
        contentHash = row.contentHash,
        schemaVersion = row.schemaVersion,
        lease = row.issuedAt?.let { Lease(it, checkNotNull(row.leaseSeq), checkNotNull(row.leaseUntil)) },
        sourceEventId = row.sourceEventId,
        blocked = row.blockedReason?.let(HeadBlock::valueOf),
        blockedRevision = row.blockedRevision,
    )

    /** DB/JPA 예외 → [EntitlementPersistenceFailure](payment 모듈 PersistenceFailures와 같은 규칙: MySQL vendor 코드·SQLState로 판단, 값 없음). */
    private fun <T> translate(entity: String, block: () -> T): T =
        try {
            block()
        } catch (e: EntitlementPersistenceFailure) {
            throw e
        } catch (e: RuntimeException) {
            throw classify(entity, e) ?: e
        }

    private fun classify(entity: String, failure: Throwable): EntitlementPersistenceFailure? {
        val chain = generateSequence(failure) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        if (chain.any { it is OptimisticLockingFailureException || it is jakarta.persistence.OptimisticLockException || it is StaleStateException }) {
            return EntitlementPersistenceFailure.OptimisticConflict(entity)
        }
        val sql = chain.filterIsInstance<SQLException>().firstOrNull() ?: return null
        return when {
            sql.errorCode == DUPLICATE_KEY -> EntitlementPersistenceFailure.UniqueConflict(KEY_NAME.find(sql.message ?: "")?.groupValues?.get(1) ?: "unknown")
            sql.errorCode == DEADLOCK -> EntitlementPersistenceFailure.Deadlock(failure, sql.errorCode, sql.sqlState)
            sql.errorCode == LOCK_WAIT_TIMEOUT -> EntitlementPersistenceFailure.LockTimeout(failure, sql.errorCode, sql.sqlState)
            sql.sqlState?.startsWith("08") == true || sql is SQLTransientConnectionException || sql is SQLNonTransientConnectionException ||
                sql is SQLRecoverableException || sql.errorCode in CONNECTION_LOST -> EntitlementPersistenceFailure.Unavailable(failure, sql.errorCode, sql.sqlState)
            else -> null
        }
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 20
        const val DUPLICATE_KEY = 1062
        const val DEADLOCK = 1213
        const val LOCK_WAIT_TIMEOUT = 1205
        val CONNECTION_LOST = setOf(2006, 2013)
        val KEY_NAME = Regex("for key '([^']+)'\\s*$")
    }
}

/** Jackson 노드 → domain [JsonValue](domain은 Jackson을 모른다). 정수가 아닌 숫자는 계약 밖이라 거절한다. */
internal object JacksonTree {
    fun toJsonValue(node: JsonNode): JsonValue = when {
        node.isNull -> JsonValue.JNull
        node.isObject -> JsonValue.JObject(node.properties().associate { (k, v) -> k to toJsonValue(v) })
        node.isArray -> JsonValue.JArray((0 until node.size()).map { toJsonValue(node.get(it)) })
        node.isString -> JsonValue.JString(node.stringValue())
        node.isBoolean -> JsonValue.JBool(node.booleanValue())
        node.isIntegralNumber && node.canConvertToLong() -> JsonValue.JNumber(node.longValue())
        else -> throw com.paycore.payment.entitlement.domain.SnapshotContractViolation("unsupported JSON value")
    }
}
