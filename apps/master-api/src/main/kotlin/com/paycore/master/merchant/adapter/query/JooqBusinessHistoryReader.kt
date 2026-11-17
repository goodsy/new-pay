package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.MASTER_CHANGE_HISTORY
import com.paycore.master.merchant.application.provided.BusinessHistoryEntry
import com.paycore.master.merchant.application.provided.BusinessHistoryPage
import com.paycore.master.merchant.application.required.BusinessHistoryReader
import com.paycore.master.merchant.domain.BusinessId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * 사업자 변경 이력(BUSINESS-PROFILE-001 §6.5, jOOQ 읽기 전용). `master_change_history`의 `aggregate_type = 'Business' AND aggregate_id = ?`(기존 색인
 * `ix_master_change_history_aggregate`) + `id > afterSeq` 오름차순, 한 쪽 [BusinessHistoryPage.PAGE_SIZE]행. detail 전체가 아니라 **`$.reason`·`$.changes`만** 읽는다
 * (옛 `BusinessRegistered` detail의 다른 키는 응답에 오지 않는다). 값은 명령 Use Case가 저장 시점에 마스킹한 값 그대로다(§9 — 원문 이력 없음).
 * MySQL JSON은 객체 키를 길이·사전순으로 다시 저장하므로 응답 키 순서를 [KEY_ORDER]로 고정한다(응답 모양 결정성 — 골든).
 */
@Component
class JooqBusinessHistoryReader(private val dsl: DSLContext) : BusinessHistoryReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun page(businessId: BusinessId, afterSeq: Long): BusinessHistoryPage? {
        val id = businessId.value
        if (dsl.selectOne().from(BUSINESS).where(BUSINESS.BUSINESS_ID.eq(id)).fetchOne() == null) return null
        val h = MASTER_CHANGE_HISTORY
        val reason = DSL.field("JSON_EXTRACT({0}, '$.reason')", String::class.java, h.DETAIL)
        val changes = DSL.field("JSON_EXTRACT({0}, '$.changes')", String::class.java, h.DETAIL)
        val rows = dsl.select(h.ID, h.CHANGE_TYPE, h.ACTOR_REF, MasterSqlText.instant(h.OCCURRED_AT), reason, changes)
            .from(h)
            .where(h.AGGREGATE_TYPE.eq(AGGREGATE_TYPE).and(h.AGGREGATE_ID.eq(id)).and(h.ID.gt(afterSeq)))
            .orderBy(h.ID)
            .limit(BusinessHistoryPage.PAGE_SIZE + 1)
            .fetch { r ->
                BusinessHistoryEntry(
                    seq = r.value1(), changeType = r.value2(), actorRef = r.value3(), occurredAt = Instant.parse(r.value4()),
                    reason = r.value5()?.let { text -> mapper.readTree(text).takeIf { it.isString }?.asString() },
                    changes = r.value6()?.let { text -> mapper.readTree(text).takeIf { it.isArray }?.let { a -> (0 until a.size()).map { i -> entry(a.get(i)) } } }.orEmpty(),
                )
            }
        val page = rows.take(BusinessHistoryPage.PAGE_SIZE)
        return BusinessHistoryPage(page, if (rows.size > BusinessHistoryPage.PAGE_SIZE) page.last().seq else null)
    }

    private companion object {
        /** 명령 Use Case가 기록하는 aggregate_type(BusinessProfileService·MerchantOnboardingService의 AGG_BUSINESS). */
        const val AGGREGATE_TYPE = "Business"

        val mapper: JsonMapper = JsonMapper.builder().build()

        /** 이력 항목·행 항목 키 순서(그 밖 키는 사전순으로 뒤에). */
        val KEY_ORDER = listOf(
            "field", "before", "after", "ended", "added", "name", "type", "postalCode", "baseAddress", "detailAddress", "email", "phoneNumber", "primary", "order",
        )

        fun ordered(node: JsonNode): Any? = when {
            node.isObject -> {
                val names = node.propertyNames().toList()
                val sorted = names.sortedWith(compareBy<String>({ KEY_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
                linkedMapOf<String, Any?>().also { map -> sorted.forEach { map[it] = ordered(node.get(it)) } }
            }
            node.isArray -> (0 until node.size()).map { ordered(node.get(it)) }
            node.isNull -> null
            node.isString -> node.asString()
            node.isBoolean -> node.asBoolean()
            node.isIntegralNumber -> node.asLong()
            node.isNumber -> node.decimalValue()
            else -> node.toString()
        }

        /** `changes` 배열의 한 항목(객체여야 한다 — 아니면 저장 결함, 조용히 꾸미지 않는다). */
        @Suppress("UNCHECKED_CAST")
        fun entry(node: JsonNode): Map<String, Any?> {
            check(node.isObject) { "a business history change entry is not an object" }
            return ordered(node) as Map<String, Any?>
        }
    }
}
