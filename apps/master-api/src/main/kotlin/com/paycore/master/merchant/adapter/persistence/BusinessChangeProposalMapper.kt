package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.AddressLine
import com.paycore.master.merchant.domain.BusinessAddress
import com.paycore.master.merchant.domain.BusinessAddressType
import com.paycore.master.merchant.domain.BusinessCategory
import com.paycore.master.merchant.domain.BusinessCategoryType
import com.paycore.master.merchant.domain.BusinessChangeEntry
import com.paycore.master.merchant.domain.BusinessChangeProposal
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContact
import com.paycore.master.merchant.domain.BusinessContent
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistration
import com.paycore.master.merchant.domain.BusinessRepresentative
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.CategoryName
import com.paycore.master.merchant.domain.CorporateRegistrationNumber
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EmailAddress
import com.paycore.master.merchant.domain.EnglishBusinessName
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.PersonName
import com.paycore.master.merchant.domain.PhoneNumber
import com.paycore.master.merchant.domain.PostalCode
import com.paycore.master.merchant.domain.RepresentativeType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * 제안 Domain 값 ↔ `business_change_proposal` 행 변환(persistence adapter — JPA Entity와 순수 Domain 분리). content는 Domain 정규 JSON([BusinessChangeProposal.contentJson])으로 쓰고
 * 읽을 때 값 객체로 다시 만든다(형식 위반은 저장 결함 — 예외). changes는 마스킹 항목 그대로(값 Map), important_fields는 문자열 배열.
 */
internal object BusinessChangeProposalMapper {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    fun changesJson(entries: List<BusinessChangeEntry>): String = mapper.writeValueAsString(
        entries.map { e ->
            when (e) {
                is BusinessChangeEntry.Value -> linkedMapOf("field" to e.field, "before" to e.before, "after" to e.after)
                is BusinessChangeEntry.Rows -> linkedMapOf("field" to e.field, "ended" to e.ended, "added" to e.added)
            }
        },
    )

    fun fieldsJson(fields: List<String>): String = mapper.writeValueAsString(fields)

    fun toDomain(row: JpaBusinessChangeProposalEntity): BusinessChangeProposal = BusinessChangeProposal.reconstitute(
        BusinessChangeProposalId(row.proposalId), BusinessId(row.businessId), row.baseVersion, content(mapper.readTree(row.content)), changes(mapper.readTree(row.changes)),
        mapper.readTree(row.importantFields).let { a -> (0 until a.size()).map { a.get(it).asString() } }, row.representativeChanged, row.subjectCorrection,
        BusinessChangeReason(row.reason), EvidenceRef(row.evidenceRef), ActorRef(row.requestedBy), row.requestedAt,
    )

    private fun content(n: JsonNode): BusinessContent {
        val r = n.get("registration")
        val registration = BusinessRegistration(
            opt(r, "corporateRegistrationNumber")?.let(::CorporateRegistrationNumber), opt(r, "openingDate")?.let(LocalDate::parse), r.get("countryCode").asString(),
            opt(r, "englishName")?.let(::EnglishBusinessName), opt(r, "phoneNumber")?.let(::PhoneNumber), opt(r, "email")?.let(::EmailAddress),
        )
        val profile = BusinessProfile(
            registration,
            list(n, "representatives") { BusinessRepresentative(PersonName(it.get("name").asString()), RepresentativeType.valueOf(it.get("type").asString())) },
            list(n, "categories") { BusinessCategory(BusinessCategoryType.valueOf(it.get("type").asString()), CategoryName(it.get("name").asString())) },
            list(n, "addresses") {
                BusinessAddress(BusinessAddressType.valueOf(it.get("type").asString()), PostalCode(it.get("postalCode").asString()), AddressLine(it.get("baseAddress").asString()),
                    opt(it, "detailAddress")?.let(::AddressLine))
            },
            list(n, "contacts") {
                BusinessContact(PersonName(it.get("name").asString()), EmailAddress(it.get("email").asString()), PhoneNumber(it.get("phoneNumber").asString()), it.get("primary").asBoolean())
            },
        )
        return BusinessContent(opt(n, "businessType")?.let(BusinessType::valueOf), DisplayName(n.get("displayName").asString()), profile)
    }

    private fun changes(a: JsonNode): List<BusinessChangeEntry> = (0 until a.size()).map { i ->
        val e = a.get(i)
        val field = e.get("field").asString()
        if (e.has("ended")) BusinessChangeEntry.Rows(field, rows(e.get("ended")), rows(e.get("added"))) else BusinessChangeEntry.Value(field, opt(e, "before"), opt(e, "after"))
    }

    private fun rows(a: JsonNode): List<Map<String, Any?>> = (0 until a.size()).map { i ->
        val o = a.get(i)
        o.propertyNames().associateWith { k ->
            val v = o.get(k)
            when {
                v.isNull -> null
                v.isBoolean -> v.asBoolean()
                v.isIntegralNumber -> v.asInt()
                else -> v.asString()
            }
        }
    }

    private fun <T> list(n: JsonNode, name: String, item: (JsonNode) -> T): List<T> = n.get(name).let { a -> (0 until a.size()).map { item(a.get(it)) } }

    private fun opt(n: JsonNode, name: String): String? = n.get(name)?.takeUnless { it.isNull }?.asString()
}
