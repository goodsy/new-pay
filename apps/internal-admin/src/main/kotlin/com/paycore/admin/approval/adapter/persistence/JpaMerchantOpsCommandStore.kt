package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.MerchantOpsCommandStore
import com.paycore.admin.approval.domain.MerchantOpsCommandKind
import com.paycore.admin.approval.domain.MerchantOpsRequestSnapshot
import com.paycore.admin.approval.domain.PinnedContractApprovalTarget
import com.paycore.admin.approval.domain.PinnedFirstMidTarget
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * [MerchantOpsCommandStore] JPA 구현(MERCHANT-OPS-002 B2-2 — `@Immutable` Entity, persist + flush, merge 금지). `pinned_target`은 domain의 결정적 JSON으로 쓰고, 읽을 때는 MySQL JSON이
 * 키 순서·공백을 정규화하므로 문자열이 아니라 필드로 엄격하게 해석한다(자료형 강제 변환 없음 — 권위 고정 값, 재매입 Store와 같은 방식).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantOpsCommandStore(private val entityManager: EntityManager) : MerchantOpsCommandStore {

    private val json: JsonMapper = JsonMapper.builder().build()

    override fun insert(command: MerchantOpsRequestSnapshot) = ApprovalPersistenceTranslation.translate {
        entityManager.persist(
            JpaMerchantOpsCommandEntity.newRow(
                command.approvalId, command.commandKind.name, command.merchantId, command.pinnedTarget.toSnapshotJson(), command.evidenceRef, command.createdAt,
            ),
        )
        entityManager.flush()
    }

    override fun find(approvalId: String): MerchantOpsRequestSnapshot? = ApprovalPersistenceTranslation.translate {
        entityManager.find(JpaMerchantOpsCommandEntity::class.java, approvalId)?.let { r ->
            val kind = MerchantOpsCommandKind.valueOf(r.commandKind)
            val pinned = when (kind) {
                MerchantOpsCommandKind.CONTRACT_APPROVAL -> pinned(r.pinnedTarget)
                MerchantOpsCommandKind.FIRST_MID_ISSUANCE -> pinnedMid(r.pinnedTarget)
            }
            MerchantOpsRequestSnapshot(r.approvalId, kind, r.merchantId, pinned, r.evidenceRef, r.createdAt)
        }
    }

    private fun pinned(text: String): PinnedContractApprovalTarget {
        val n = json.readTree(text)
        check(n.isObject && n.propertyNames().toSet() == FIELDS) { "pinned_target shape" }
        val sites = n.required("scopeSiteIds").also { check(it.isArray) { "pinned_target scopeSiteIds" } }
        val to = n.required("effectiveTo")
        val approved = n.required("approved").also { check(it.isBoolean) { "pinned_target approved" } }.asBoolean()
        return PinnedContractApprovalTarget(
            merchantId = text(n, "merchantId"), merchantName = text(n, "merchantName"), contractId = text(n, "contractId"),
            revisionNo = n.required("revisionNo").also { check(it.isIntegralNumber && it.canConvertToInt()) { "pinned_target revisionNo" } }.asInt(),
            scopeKind = text(n, "scopeKind"), scopeSiteIds = (0 until sites.size()).map { i -> sites.get(i).also { check(it.isString) { "site id type" } }.asString() },
            effectiveFrom = Instant.parse(text(n, "effectiveFrom")), effectiveTo = if (to.isNull) null else Instant.parse(text(n, "effectiveTo")),
            registeredBy = text(n, "registeredBy"), approved = approved,
        )
    }

    /** B2-3 첫 MID 고정 대상(필드 집합 정확 일치 — 자료형 강제 변환 없음). */
    private fun pinnedMid(text: String): PinnedFirstMidTarget {
        val n = json.readTree(text)
        check(n.isObject && n.propertyNames().toSet() == MID_FIELDS) { "pinned_target shape (first MID)" }
        val to = n.required("effectiveTo")
        return PinnedFirstMidTarget(
            merchantId = text(n, "merchantId"), merchantName = text(n, "merchantName"), contractId = text(n, "contractId"), agreementId = text(n, "agreementId"),
            revisionNo = int(n, "revisionNo"), effectiveFrom = Instant.parse(text(n, "effectiveFrom")), effectiveTo = if (to.isNull) null else Instant.parse(text(n, "effectiveTo")),
            primarySiteId = text(n, "primarySiteId"), channelType = text(n, "channelType"), accountCount = int(n, "accountCount"),
            approved = n.required("approved").also { check(it.isBoolean) { "pinned_target approved" } }.asBoolean(),
        )
    }

    private fun int(node: JsonNode, name: String): Int = node.required(name).also { check(it.isIntegralNumber && it.canConvertToInt()) { "pinned_target $name" } }.asInt()

    private fun text(node: JsonNode, name: String): String = node.required(name).also { check(it.isString) { "pinned_target $name type" } }.asString()

    private companion object {
        val MID_FIELDS = setOf(
            "merchantId", "merchantName", "accountCount", "contractId", "agreementId", "revisionNo", "approved", "effectiveFrom", "effectiveTo", "primarySiteId", "channelType",
        )
        val FIELDS = setOf(
            "merchantId", "merchantName", "contractId", "revisionNo", "scopeKind", "scopeSiteIds", "effectiveFrom", "effectiveTo", "approved", "registeredBy",
        )
    }
}
