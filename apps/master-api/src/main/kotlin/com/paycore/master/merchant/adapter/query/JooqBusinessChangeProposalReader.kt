package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.BUSINESS_CHANGE_EVIDENCE
import com.paycore.master.jooq.generated.Tables.BUSINESS_CHANGE_PROPOSAL
import com.paycore.master.merchant.application.provided.BusinessApprovalState
import com.paycore.master.merchant.application.provided.BusinessChangeEvidenceView
import com.paycore.master.merchant.application.provided.BusinessChangeProposalOriginal
import com.paycore.master.merchant.application.provided.BusinessChangeProposalView
import com.paycore.master.merchant.application.required.BusinessChangeProposalReader
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.Lifecycle
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * 사업자 중요정보 변경 조회(BIZ-CRIT-APPROVAL-001 §5.1 — jOOQ 읽기 전용, CQRS·비권위). 모두 사업자 범위(`business_id = ?` 조건 — 다른 사업자의 제안·이력 ID는 null).
 * 제안 조회는 저장된 마스킹 항목(changes)·항목 이름·표시값만, 원문 조회만 content(원문 JSON)를 읽는다. 1인 근거 원문은 근거 표에서 이력 행 ID + 사업자로 읽는다.
 * MySQL JSON은 객체 키를 다시 정렬해 저장하므로 원문·항목의 키 순서는 저장 순서가 아니다(값 대조용 — 정규 바이트가 필요하면 Domain 정규 JSON을 쓴다).
 */
@Component
class JooqBusinessChangeProposalReader(private val dsl: DSLContext) : BusinessChangeProposalReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun approvalState(businessId: BusinessId): BusinessApprovalState? =
        dsl.select(BUSINESS.VERSION, BUSINESS.LIFECYCLE, BUSINESS.REGISTRATION_STATUS).from(BUSINESS).where(BUSINESS.BUSINESS_ID.eq(businessId.value))
            .fetchOne { r -> BusinessApprovalState(r.value1()!!, Lifecycle.valueOf(r.value2()!!), BusinessRegistrationStatus.valueOf(r.value3()!!)) }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun proposal(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalView? {
        val p = BUSINESS_CHANGE_PROPOSAL
        val displayName = DSL.field("JSON_UNQUOTE(JSON_EXTRACT({0}, '$.displayName'))", String::class.java, p.CONTENT)
        return dsl.select(p.BASE_VERSION, p.CONTENT_DIGEST, displayName, text(p.CHANGES), text(p.IMPORTANT_FIELDS), p.SUBJECT_CORRECTION, p.REQUESTED_BY, MasterSqlText.instant(p.REQUESTED_AT))
            .from(p).where(p.PROPOSAL_ID.eq(proposalId.value).and(p.BUSINESS_ID.eq(businessId.value)))
            .fetchOne { r ->
                val changes = mapper.readTree(r.value4()).let { a -> (0 until a.size()).map { plain(a.get(it)) as Map<*, *> }.map(::ordered) }
                val important = mapper.readTree(r.value5()).let { a -> (0 until a.size()).map { a.get(it).asString() } }
                BusinessChangeProposalView(
                    proposalId = proposalId.value, businessId = businessId.value, baseVersion = r.value1()!!, contentDigest = r.value2()!!, displayName = r.value3()!!,
                    changedFields = changes.map { it["field"] as String }, importantFields = important,
                    // Coordinator 결정(S1 리뷰 질문 2): 대표자 표시는 대표자 변경이 '중요'로 판정된 경우만.
                    representativeChanged = REPRESENTATIVES in important, subjectCorrection = r.value6()?.toInt() == 1,
                    changes = changes, requestedBy = r.value7()!!, requestedAt = Instant.parse(r.value8()),
                )
            }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun original(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalOriginal? {
        val p = BUSINESS_CHANGE_PROPOSAL
        return dsl.select(text(p.CONTENT)).from(p).where(p.PROPOSAL_ID.eq(proposalId.value).and(p.BUSINESS_ID.eq(businessId.value)))
            .fetchOne { r ->
                @Suppress("UNCHECKED_CAST")
                BusinessChangeProposalOriginal(proposalId.value, businessId.value, plain(mapper.readTree(r.value1())) as Map<String, Any?>)
            }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun evidence(businessId: BusinessId, historyId: Long): BusinessChangeEvidenceView? {
        val e = BUSINESS_CHANGE_EVIDENCE
        return dsl.select(e.EVIDENCE_REF, e.RECORDED_BY, MasterSqlText.instant(e.RECORDED_AT)).from(e)
            .where(e.CHANGE_HISTORY_ID.eq(historyId).and(e.BUSINESS_ID.eq(businessId.value)))
            .fetchOne { r -> BusinessChangeEvidenceView(historyId, businessId.value, r.value1()!!, r.value2()!!, Instant.parse(r.value3())) }
    }

    private companion object {
        const val REPRESENTATIVES = "representatives"
        val mapper: JsonMapper = JsonMapper.builder().build()

        /** 이력 항목 키 순서(이력 조회와 같은 관례 — field·before·after·ended·added 먼저). */
        val KEY_ORDER = listOf("field", "before", "after", "ended", "added", "name", "type", "postalCode", "baseAddress", "detailAddress", "email", "phoneNumber", "primary", "order")

        fun text(field: Field<*>): Field<String?> = DSL.field("CAST({0} AS CHAR)", String::class.java, field)

        fun plain(node: JsonNode): Any? = when {
            node.isObject -> linkedMapOf<String, Any?>().also { m -> node.propertyNames().forEach { m[it] = plain(node.get(it)) } }
            node.isArray -> (0 until node.size()).map { plain(node.get(it)) }
            node.isNull -> null
            node.isBoolean -> node.asBoolean()
            node.isIntegralNumber -> node.asLong()
            else -> node.asString()
        }

        @Suppress("UNCHECKED_CAST")
        fun ordered(map: Map<*, *>): Map<String, Any?> {
            val sorted = map.keys.map { it as String }.sortedWith(compareBy<String>({ KEY_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
            return linkedMapOf<String, Any?>().also { out ->
                sorted.forEach { k ->
                    val v = map[k]
                    out[k] = when (v) {
                        is Map<*, *> -> ordered(v)
                        is List<*> -> v.map { if (it is Map<*, *>) ordered(it) else it }
                        else -> v
                    }
                }
            }
        }
    }
}
