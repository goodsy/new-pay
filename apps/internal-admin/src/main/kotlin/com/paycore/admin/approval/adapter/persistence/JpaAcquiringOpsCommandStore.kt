package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.AcquiringOpsCommandStore
import com.paycore.admin.approval.domain.PinnedReacquisitionItem
import com.paycore.admin.approval.domain.PinnedReacquisitionTarget
import com.paycore.admin.approval.domain.ReacquisitionRequestSnapshot
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * [AcquiringOpsCommandStore] JPA 구현(ACQ-ADMIN-001 B1-A — `@Immutable` Entity, persist + flush, merge 금지). `pinned_target`은 domain의 결정적 JSON으로 쓰고, 읽을 때는 MySQL JSON이
 * 키 순서·공백을 정규화하므로 문자열이 아니라 필드로 엄격하게 해석한다(자료형 강제 변환 없음 — 권위 고정 값, 운영자 보정 취소 Store와 같은 방식).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquiringOpsCommandStore(private val entityManager: EntityManager) : AcquiringOpsCommandStore {

    private val json: JsonMapper = JsonMapper.builder().build()

    override fun insert(command: ReacquisitionRequestSnapshot) = translate {
        entityManager.persist(
            JpaAcquiringOpsCommandEntity.newRow(
                command.approvalId, command.originalFileId, command.reasonCode, command.evidenceRef, command.reason, command.pinnedTarget.toSnapshotJson(), command.createdAt,
            ),
        )
        entityManager.flush()
    }

    override fun find(approvalId: String): ReacquisitionRequestSnapshot? = translate {
        entityManager.find(JpaAcquiringOpsCommandEntity::class.java, approvalId)?.let { r ->
            ReacquisitionRequestSnapshot(r.approvalId, r.originalFileId, r.reasonCode, r.evidenceRef, r.reason, pinned(r.pinnedTarget), r.createdAt)
        }
    }

    private fun pinned(text: String): PinnedReacquisitionTarget {
        val node = json.readTree(text)
        check(node.isObject && node.propertyNames().toSet() == setOf("fileId", "items")) { "pinned_target shape" }
        val items = node.required("items").also { check(it.isArray) { "pinned_target items" } }
        return PinnedReacquisitionTarget(
            text(node, "fileId"),
            (0 until items.size()).map { i ->
                val n = items.get(i)
                check(n.isObject && n.propertyNames().toSet() == ITEM_FIELDS) { "pinned_target item shape" }
                PinnedReacquisitionItem(
                    int(n, "seq"), text(n, "transactionId"), text(n, "recordType"), int(n, "generation"), text(n, "status"), long(n, "approvedAmountMinor"),
                    long(n, "cancelledAmountMinor"), long(n, "expectedNetMinor"),
                )
            },
        )
    }

    private fun text(node: JsonNode, name: String): String = node.required(name).also { check(it.isString) { "pinned_target $name type" } }.asString()

    private fun long(node: JsonNode, name: String): Long = node.required(name).also { check(it.isIntegralNumber && it.canConvertToLong()) { "pinned_target $name" } }.asLong()

    private fun int(node: JsonNode, name: String): Int = node.required(name).also { check(it.isIntegralNumber && it.canConvertToInt()) { "pinned_target $name" } }.asInt()

    private fun <T> translate(block: () -> T): T = ApprovalPersistenceTranslation.translate(block)

    private companion object {
        val ITEM_FIELDS = setOf(
            "seq", "transactionId", "recordType", "generation", "status", "approvedAmountMinor", "cancelledAmountMinor", "expectedNetMinor",
        )
    }
}
