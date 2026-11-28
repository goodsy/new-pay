package com.paycore.master.merchant.domain

/**
 * 사업자 변경 계산(명세 §4.5 표 — POL-004, C-12, M-2·M-4). 이전·이후 [BusinessContent]를 비교해 바뀐 항목([entries] — 값은 §9 마스킹만)과 중요 변경 여부([important])를 낸다.
 *
 * - 중요: 상호·사업체 구분(migration 행의 NULL → 첫 값 제외)·개업일/법인등록번호의 기존 값 변경·삭제·대표자(아래).
 * - 대표자: 이전 상태 REGISTERED면 현재 대표자 집합의 **모든** 변경(추가·종료·유형·순서)이 중요(M-2). DRAFT면 기존 행 종료가 있을 때만 중요(0명에서 첫 입력·유지 + 추가는 일반).
 * - 그 밖(업태·종목·주소·사업체 연락처·영문 상호·등록 담당자)은 일반.
 * - 하위 행 동일성 = 내용 + 순서(§4.1·C-10): 같은 키 = 유지, 그 밖 = 종료(`ended`) + 추가(`added`). 순서만 바뀌어도 종료 + 추가(M-4).
 *   업태·종목 순서는 유형 안 순서([BusinessProfile.orderedCategories]), 주소는 유형으로 구별(순서 없음).
 */
class BusinessProfileChange private constructor(
    val entries: List<BusinessChangeEntry>,
    val important: Boolean,
    /** BIZ-CRIT-APPROVAL-001 §3.4 파생값: 중요 판정에 기여한 항목 이름(변경 계산 순서, 생성이면 빈 목록). [important] = 이 목록이 비어 있지 않음. */
    val importantFields: List<String>,
    /** §3.4 파생값: 현재 대표자 집합이 바뀜(추가·종료·유형·순서 — 일반/중요 무관, 생성이면 false). */
    val representativeChanged: Boolean,
    /** §3.4·§3.6 파생값: 기존 값 있는 사업체 구분 변경 또는 기존 법인등록번호 변경·삭제(첫 입력 제외, 생성이면 false). */
    val subjectCorrection: Boolean,
) {

    val isEmpty: Boolean get() = entries.isEmpty()

    /** 등록 완료(M-1): 상태 전이 항목을 맨 앞에 붙인다(내용 변경이 없어도 이력 1건). */
    fun withStatusTransition(before: BusinessRegistrationStatus, after: BusinessRegistrationStatus): BusinessProfileChange =
        BusinessProfileChange(
            listOf(BusinessChangeEntry.Value(REGISTRATION_STATUS, before.name, after.name)) + entries, important, importantFields, representativeChanged, subjectCorrection,
        )

    override fun toString(): String = "BusinessProfileChange(fields=${entries.map { it.field }}, important=$important, subjectCorrection=$subjectCorrection)"

    companion object {
        const val REGISTRATION_STATUS = "registrationStatus"
        const val REPRESENTATIVES = "representatives"

        /** BIZ-CRIT-APPROVAL-001 §3.6 정정 대상 항목 이름(기존 값 있는 사업체 구분·법인등록번호). */
        val SUBJECT_FIELDS: Set<String> = setOf("businessType", "registration.corporateRegistrationNumber")

        /** 생성: 입력 항목 전부(이전 값 없음 — before null), 중요 여부 없음. */
        fun created(after: BusinessContent): BusinessProfileChange = compute(BusinessRegistrationStatus.DRAFT, null, after)

        fun of(beforeStatus: BusinessRegistrationStatus, before: BusinessContent, after: BusinessContent): BusinessProfileChange =
            compute(beforeStatus, before, after)

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        private fun compute(beforeStatus: BusinessRegistrationStatus, before: BusinessContent?, after: BusinessContent): BusinessProfileChange {
            val entries = mutableListOf<BusinessChangeEntry>()
            val importantFields = mutableListOf<String>()
            val b = before?.profile?.canonical()
            val a = after.profile.canonical()

            fun value(field: String, changed: Boolean, old: String?, new: String?, importance: Boolean) {
                if (!changed) return
                entries += BusinessChangeEntry.Value(field, old, new)
                if (before != null && importance) importantFields += field
            }

            // 중요(§4.5): 상호, 구분(migration 행의 NULL → 첫 값 제외), 개업일·법인등록번호(비어 있던 값의 첫 입력 제외 — 기존 값 변경·삭제만).
            // 비교는 원문, 기록은 마스킹(마스킹 값이 같아도 원문이 바뀌면 기록 — 예: 끝 4자리가 같은 전화번호).
            value("displayName", before?.displayName != after.displayName, before?.displayName?.value, after.displayName.value, importance = true)
            value("businessType", before?.businessType != after.businessType, before?.businessType?.name, after.businessType?.name, importance = before?.businessType != null)
            val oldReg = b?.registration
            val newReg = a.registration
            value(
                "registration.corporateRegistrationNumber", oldReg?.corporateRegistrationNumber != newReg.corporateRegistrationNumber,
                oldReg?.corporateRegistrationNumber?.masked(), newReg.corporateRegistrationNumber?.masked(), importance = oldReg?.corporateRegistrationNumber != null,
            )
            value(
                "registration.openingDate", oldReg?.openingDate != newReg.openingDate, oldReg?.openingDate?.toString(), newReg.openingDate?.toString(),
                importance = oldReg?.openingDate != null,
            )
            // 일반: 영문 상호·사업체 연락처(전화·이메일 — 마스킹).
            value("registration.englishName", oldReg?.englishName != newReg.englishName, oldReg?.englishName?.value, newReg.englishName?.value, importance = false)
            value(
                "registration.phoneNumber", oldReg?.phoneNumber != newReg.phoneNumber, oldReg?.phoneNumber?.value?.let(BusinessPiiMask::phone),
                newReg.phoneNumber?.value?.let(BusinessPiiMask::phone), importance = false,
            )
            value(
                "registration.email", oldReg?.email != newReg.email, oldReg?.email?.value?.let(BusinessPiiMask::email), newReg.email?.value?.let(BusinessPiiMask::email),
                importance = false,
            )
            val reps = rows(
                REPRESENTATIVES,
                b?.representatives.orEmpty().mapIndexed { i, r -> r to i + 1 },
                a.representatives.mapIndexed { i, r -> r to i + 1 },
            ) { r, order -> mapOf("name" to BusinessPiiMask.name(r.name.value), "type" to r.type.name, "order" to order) }
            if (reps != null) {
                entries += reps
                if (before != null) {
                    // M-2: REGISTERED는 대표자 집합의 모든 변경이 중요. DRAFT는 기존 행 종료가 있을 때만(0명 → 첫 입력·유지 + 추가는 일반).
                    if (beforeStatus == BusinessRegistrationStatus.REGISTERED || reps.ended.isNotEmpty()) importantFields += reps.field
                }
            }
            rows("categories", b?.orderedCategories().orEmpty(), a.orderedCategories()) { c, order ->
                mapOf("type" to c.type.name, "name" to c.name.value, "order" to order)
            }?.let { entries += it }
            rows("addresses", b?.addresses.orEmpty().map { it to 0 }, a.addresses.map { it to 0 }) { addr, _ ->
                mapOf(
                    "type" to addr.type.name, "postalCode" to addr.postalCode.value, "baseAddress" to BusinessPiiMask.baseAddress(addr.baseAddress.value),
                    "detailAddress" to addr.detailAddress?.value?.let(BusinessPiiMask::detailAddress),
                )
            }?.let { entries += it }
            rows("contacts", b?.contacts.orEmpty().mapIndexed { i, c -> c to i + 1 }, a.contacts.mapIndexed { i, c -> c to i + 1 }) { c, order ->
                mapOf(
                    "name" to BusinessPiiMask.name(c.name.value), "email" to BusinessPiiMask.email(c.email.value), "phoneNumber" to BusinessPiiMask.phone(c.phoneNumber.value),
                    "primary" to c.primary, "order" to order,
                )
            }?.let { entries += it }
            // BIZ-CRIT-APPROVAL-001 §3.6: 정정 대상 = 기존 값이 있던 구분·법인등록번호의 중요 변경(첫 입력은 위 importance 규칙상 중요가 아니다).
            val subjectCorrection = importantFields.any { it in SUBJECT_FIELDS }
            return BusinessProfileChange(
                entries.sortedBy { FIELD_ORDER.indexOf(it.field) }, importantFields.isNotEmpty(), importantFields.sortedBy { FIELD_ORDER.indexOf(it) },
                representativeChanged = before != null && reps != null, subjectCorrection = subjectCorrection,
            )
        }

        /** 동일성 키 = (내용, 순서). 바뀐 것이 없으면 null. */
        private fun <T> rows(
            field: String,
            before: List<Pair<T, Int>>,
            after: List<Pair<T, Int>>,
            masked: (T, Int) -> Map<String, Any?>,
        ): BusinessChangeEntry.Rows? {
            val beforeKeys = before.toSet()
            val afterKeys = after.toSet()
            val ended = before.filter { it !in afterKeys }
            val added = after.filter { it !in beforeKeys }
            if (ended.isEmpty() && added.isEmpty()) return null
            return BusinessChangeEntry.Rows(field, ended.map { (v, o) -> masked(v, o) }, added.map { (v, o) -> masked(v, o) })
        }

        private val FIELD_ORDER = listOf(
            REGISTRATION_STATUS, "displayName", "businessType", "registration.corporateRegistrationNumber", "registration.openingDate",
            "registration.englishName", "registration.phoneNumber", "registration.email", "representatives", "categories", "addresses", "contacts",
        )
    }
}

/** 이력 `changes` 항목(값은 마스킹 — §9). */
sealed interface BusinessChangeEntry {
    val field: String

    /** 단일 값: 생성이면 [before] = null. */
    data class Value(override val field: String, val before: String?, val after: String?) : BusinessChangeEntry

    /** 하위 행 목록: 종료된 행·추가된 행(마스킹된 키-값). */
    data class Rows(override val field: String, val ended: List<Map<String, Any?>>, val added: List<Map<String, Any?>>) : BusinessChangeEntry
}

/** 변경 계산 입력(사업체 구분 — migration 행은 null, 상호, 하위 정보). */
data class BusinessContent(val businessType: BusinessType?, val displayName: DisplayName, val profile: BusinessProfile)
