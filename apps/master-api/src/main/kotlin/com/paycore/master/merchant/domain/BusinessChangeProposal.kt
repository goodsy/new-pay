package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 사업자 중요정보 변경 제안(BIZ-CRIT-APPROVAL-001 §3.2·§4.2 — 순수 Domain 값, JPA Entity와 분리). 2인 칸(§1.1 [BusinessChangeApprovalLevel.requiresApproval])의 한 저장을
 * **통째로**(일반 항목 포함 — P-8) 담는다. 상태 없음(INSERT 전용) — '적용됨'은 근거 EXECUTED 행이 이 제안을 가리키는 것으로 안다.
 *
 * - [content]: 변경 후 사업자 내용 전체(구분·상호·정규 profile). 사업자등록번호는 들어가지 않는다(불변 — P-6, 제안·실행 경로에 번호 필드 없음).
 * - [changes]: 제안 시점 기준 변경 항목(값은 §9 마스킹만 — 승인 상세 표시용). [changedFields]·[importantFields]·[representativeChanged]·[subjectCorrection]은 ADMIN pinned 값의 원천.
 * - digest: [digestInput](정규 JSON — content + businessId + baseVersion)을 adapter가 MASTER fingerprint 키로 HMAC한다([BusinessChangeDigest]). 실행 때 저장된 내용으로 **다시 계산**해 승인 명령의 digest와 비교한다.
 *
 * 불변식(생성·복원 공통): baseVersion ≥ 0, 변경 항목 있음, 중요 항목 있음(2인 칸은 중요 변경에서만 생긴다), 중요 항목 ⊆ 변경 항목, [subjectCorrection] ⇔ 구분 또는 법인등록번호가 중요 항목,
 * [representativeChanged] ⇔ 대표자가 변경 항목,
 * content 구분 있음, content profile은 정규형. toString에는 ID·version·항목 이름만(상호·개인정보·사유·근거 없음).
 */
@Suppress("LongParameterList")
class BusinessChangeProposal private constructor(
    val id: BusinessChangeProposalId,
    val businessId: BusinessId,
    val baseVersion: Long,
    val content: BusinessContent,
    val changes: List<BusinessChangeEntry>,
    val importantFields: List<String>,
    val representativeChanged: Boolean,
    val subjectCorrection: Boolean,
    val reason: BusinessChangeReason,
    val evidenceRef: EvidenceRef,
    val requestedBy: ActorRef,
    val requestedAt: Instant,
) {
    init {
        require(baseVersion >= 0) { "proposal base version must not be negative" }
        require(changes.isNotEmpty()) { "a proposal must change something" }
        require(importantFields.isNotEmpty()) { "a proposal must contain an important change" }
        require(changedFields.containsAll(importantFields)) { "important fields must be changed fields" }
        require(subjectCorrection == importantFields.any { it in BusinessProfileChange.SUBJECT_FIELDS }) {
            "subjectCorrection must match whether the business type or corporate number is an important field"
        }
        require(representativeChanged == (BusinessProfileChange.REPRESENTATIVES in changedFields)) { "representativeChanged must match whether representatives changed" }
        require(content.businessType != null) { "proposal content must have a business type" }
        require(content.profile == content.profile.canonical()) { "proposal content profile must be canonical" }
    }

    /** 바뀐 항목 이름(변경 계산 순서). */
    val changedFields: List<String> get() = changes.map { it.field }

    /** 실행 때 재계산한 변경이 이 제안의 요약(항목·중요 항목·정정·대표자 표시)과 같은지(MINOR-2 — 다르면 무결성 결함). */
    fun summaryMatches(change: BusinessProfileChange): Boolean =
        change.entries.map { it.field } == changedFields && change.importantFields == importantFields &&
            change.subjectCorrection == subjectCorrection && change.representativeChanged == representativeChanged

    /** 저장·digest용 정규 JSON(CanonicalJson — 구분·상호·등록정보·하위 행 전체, 원문). MASTER 제안 표에만 저장한다(M-5). */
    fun contentJson(): String = CanonicalJson.write(contentMap())

    /** digest 입력(정규 JSON): {baseVersion, businessId, content}. 키·HMAC은 adapter. */
    fun digestInput(): String = CanonicalJson.write(mapOf("baseVersion" to baseVersion, "businessId" to businessId.value, "content" to contentMap()))

    /** 정규 내용(키 사전순은 [CanonicalJson]이 한다). 하위 행 순서 = 정규 profile 순서(업태 → 종목, 주소 REGISTERED → ACTUAL, 대표자·담당자 입력 순서). */
    private fun contentMap(): Map<String, Any?> {
        val p = content.profile
        val r = p.registration
        return mapOf(
            "businessType" to content.businessType?.name,
            "displayName" to content.displayName.value,
            "registration" to mapOf(
                "corporateRegistrationNumber" to r.corporateRegistrationNumber?.value, "openingDate" to r.openingDate?.toString(), "countryCode" to r.countryCode,
                "englishName" to r.englishName?.value, "phoneNumber" to r.phoneNumber?.value, "email" to r.email?.value,
            ),
            "representatives" to p.representatives.map { mapOf("name" to it.name.value, "type" to it.type.name) },
            "categories" to p.categories.map { mapOf("type" to it.type.name, "name" to it.name.value) },
            "addresses" to p.addresses.map {
                mapOf("type" to it.type.name, "postalCode" to it.postalCode.value, "baseAddress" to it.baseAddress.value, "detailAddress" to it.detailAddress?.value)
            },
            "contacts" to p.contacts.map { mapOf("name" to it.name.value, "email" to it.email.value, "phoneNumber" to it.phoneNumber.value, "primary" to it.primary) },
        )
    }

    override fun toString(): String =
        "BusinessChangeProposal(id=${id.value}, businessId=${businessId.value}, baseVersion=$baseVersion, fields=$changedFields, subjectCorrection=$subjectCorrection)"

    companion object {
        /** [Business.proposeCriticalChange] 전용 — 판정이 끝난 변경으로 만든다. */
        @Suppress("LongParameterList")
        internal fun of(
            id: BusinessChangeProposalId,
            businessId: BusinessId,
            baseVersion: Long,
            content: BusinessContent,
            change: BusinessProfileChange,
            reason: BusinessChangeReason,
            evidenceRef: EvidenceRef,
            requestedBy: ActorRef,
            requestedAt: Instant,
        ) = BusinessChangeProposal(
            id, businessId, baseVersion, content.copy(profile = content.profile.canonical()), change.entries, change.importantFields, change.representativeChanged,
            change.subjectCorrection, reason, evidenceRef, requestedBy, requestedAt,
        )

        /** persistence Mapper 전용 복원(S2). */
        @Suppress("LongParameterList")
        fun reconstitute(
            id: BusinessChangeProposalId,
            businessId: BusinessId,
            baseVersion: Long,
            content: BusinessContent,
            changes: List<BusinessChangeEntry>,
            importantFields: List<String>,
            representativeChanged: Boolean,
            subjectCorrection: Boolean,
            reason: BusinessChangeReason,
            evidenceRef: EvidenceRef,
            requestedBy: ActorRef,
            requestedAt: Instant,
        ) = BusinessChangeProposal(
            id, businessId, baseVersion, content, changes, importantFields, representativeChanged, subjectCorrection, reason, evidenceRef, requestedBy, requestedAt,
        )
    }
}
