package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.MerchantOpsCommandStore
import com.paycore.admin.approval.domain.MerchantOpsCommandKind
import com.paycore.admin.approval.domain.MerchantOpsRequestSnapshot
import com.paycore.admin.approval.domain.PinnedContractApprovalTarget
import com.paycore.admin.approval.domain.PinnedCurrencyEnablementTarget
import com.paycore.admin.approval.domain.PinnedActivationApprovalTarget
import com.paycore.admin.approval.domain.PinnedFirstMidTarget
import com.paycore.admin.approval.domain.PinnedSiteReviewApprovalTarget
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
                MerchantOpsCommandKind.PAYMENT_METHOD_ACTIVATION -> pinnedActivation(r.pinnedTarget)
                MerchantOpsCommandKind.SITE_REVIEW_APPROVAL -> pinnedSiteReview(r.pinnedTarget)
                MerchantOpsCommandKind.CURRENCY_ENABLEMENT -> pinnedCurrency(r.pinnedTarget)
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

    /** B3-1 개통 승인 고정 대상(문자열 필드 8개 정확 일치). */
    private fun pinnedActivation(text: String): PinnedActivationApprovalTarget {
        val n = json.readTree(text)
        check(n.isObject && n.propertyNames().toSet() == ACTIVATION_FIELDS) { "pinned_target shape (activation)" }
        return PinnedActivationApprovalTarget(
            merchantId = text(n, "merchantId"), merchantName = text(n, "merchantName"), merchantAccountId = text(n, "merchantAccountId"), activationId = text(n, "activationId"),
            paymentMethod = text(n, "paymentMethod"), status = text(n, "status"), primarySiteId = text(n, "primarySiteId"), registeredBy = text(n, "registeredBy"),
        )
    }

    /** B3-1 Site 심사 승인 고정 대상(문자열 필드 7개 정확 일치). */
    /** B3-2 통화 활성화 고정 대상(필드 8개 정확 일치 — 날짜 두 개는 KST 날짜 문자열 또는 JSON null, 자료형 강제 변환 없음). */
    private fun pinnedCurrency(text: String): PinnedCurrencyEnablementTarget {
        val n = json.readTree(text)
        check(n.isObject && n.propertyNames().toSet() == CURRENCY_FIELDS) { "pinned_target shape (currency)" }
        fun date(name: String) = n.required(name).let { if (it.isNull) null else java.time.LocalDate.parse(text(n, name)) }
        return PinnedCurrencyEnablementTarget(
            merchantId = text(n, "merchantId"), merchantName = text(n, "merchantName"), merchantAccountId = text(n, "merchantAccountId"),
            accountLifecycle = text(n, "accountLifecycle"), currency = text(n, "currency"), effectiveFromMode = text(n, "effectiveFromMode"),
            effectiveFrom = date("effectiveFrom"), effectiveTo = date("effectiveTo"),
        )
    }

    private fun pinnedSiteReview(text: String): PinnedSiteReviewApprovalTarget {
        val n = json.readTree(text)
        check(n.isObject && n.propertyNames().toSet() == SITE_REVIEW_FIELDS) { "pinned_target shape (site review)" }
        return PinnedSiteReviewApprovalTarget(
            merchantId = text(n, "merchantId"), merchantName = text(n, "merchantName"), merchantSiteId = text(n, "merchantSiteId"), channelType = text(n, "channelType"),
            reviewStatus = text(n, "reviewStatus"), reasonCode = text(n, "reasonCode"), registeredBy = text(n, "registeredBy"),
        )
    }

    private fun int(node: JsonNode, name: String): Int = node.required(name).also { check(it.isIntegralNumber && it.canConvertToInt()) { "pinned_target $name" } }.asInt()

    private fun text(node: JsonNode, name: String): String = node.required(name).also { check(it.isString) { "pinned_target $name type" } }.asString()

    private companion object {
        val ACTIVATION_FIELDS = setOf("merchantId", "merchantName", "merchantAccountId", "activationId", "paymentMethod", "status", "primarySiteId", "registeredBy")
        val CURRENCY_FIELDS = setOf(
            "merchantId", "merchantName", "merchantAccountId", "accountLifecycle", "currency", "effectiveFromMode", "effectiveFrom", "effectiveTo",
        )
        val SITE_REVIEW_FIELDS = setOf("merchantId", "merchantName", "merchantSiteId", "channelType", "reviewStatus", "reasonCode", "registeredBy")
        val MID_FIELDS = setOf(
            "merchantId", "merchantName", "accountCount", "contractId", "agreementId", "revisionNo", "approved", "effectiveFrom", "effectiveTo", "primarySiteId", "channelType",
        )
        val FIELDS = setOf(
            "merchantId", "merchantName", "contractId", "revisionNo", "scopeKind", "scopeSiteIds", "effectiveFrom", "effectiveTo", "approved", "registeredBy",
        )
    }
}
