package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.OperatorCancelCommandStore
import com.paycore.admin.approval.domain.OperatorCancelPurposeKind
import com.paycore.admin.approval.domain.OperatorCancelRequestSnapshot
import com.paycore.admin.approval.domain.PinnedPaymentTarget
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * [OperatorCancelCommandStore] JPA 구현(ADMIN-PAYMENT-OPS-001 §5 — `@Immutable` Entity, persist만, merge 금지). `pinned_target`은 domain의 결정적 JSON
 * (`PinnedPaymentTarget.toSnapshotJson`)으로 쓰고, 읽을 때는 MySQL JSON이 키 순서·공백을 정규화하므로 문자열이 아니라 필드로 해석한다(Mapper).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOperatorCancelCommandStore(private val entityManager: EntityManager) : OperatorCancelCommandStore {

    private val json: JsonMapper = JsonMapper.builder().build()

    override fun insert(command: OperatorCancelRequestSnapshot) = translate {
        entityManager.persist(
            JpaOperatorCancelCommandEntity.newRow(
                command.approvalId, command.paymentId, command.amountMinor, command.currency, command.purposeKind.name, command.purposeReference, command.reason,
                command.pinnedTarget.toSnapshotJson(), command.createdAt,
            ),
        )
        entityManager.flush()
    }

    override fun find(approvalId: String): OperatorCancelRequestSnapshot? = translate {
        entityManager.find(JpaOperatorCancelCommandEntity::class.java, approvalId)?.let { r ->
            OperatorCancelRequestSnapshot.of(
                r.approvalId, r.paymentId, r.amountMinor, r.currency, OperatorCancelPurposeKind.valueOf(r.purposeKind), r.purposeRef, r.reason, pinned(r.pinnedTarget),
                r.createdAt,
            )
        }
    }

    private fun pinned(text: String): PinnedPaymentTarget {
        val node = json.readTree(text)
        // S4 리뷰 L-4: 자료형을 엄격하게(문자열 자리에 숫자·불리언 자리에 문자열이면 강제 변환하지 않고 실패 — 권위 고정 값).
        fun text(name: String): String = node.required(name).also { check(it.isString) { "pinned_target $name type" } }.asString()
        fun textOrNull(name: String): String? = node.required(name).let { if (it.isNull) null else text(name) }
        fun longOrNull(name: String): Long? = node.required(name).let { if (it.isNull) null else exactLong(it) }
        return PinnedPaymentTarget(
            paymentId = text("paymentId"), paymentStatus = text("paymentStatus"), currency = textOrNull("currency"),
            authorizedMinor = longOrNull("authorizedMinor"), cancellableMinor = exactLong(node.required("cancellableMinor")),
            balanceRevision = exactLong(node.required("balanceRevision")),
            onHold = node.required("onHold").also { check(it.isBoolean) { "pinned_target onHold type" } }.asBoolean(),
        )
    }

    private fun exactLong(node: JsonNode): Long {
        check(node.isIntegralNumber && node.canConvertToLong()) { "pinned_target number" }
        return node.asLong()
    }

    private fun <T> translate(block: () -> T): T = ApprovalPersistenceTranslation.translate(block)
}
