package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 사업자 조회(BUSINESS-PROFILE-001 §6.2~§6.5·§6.7 — BIZ-2, CQRS 조회 측). 구현은 Application Query Service가 required `…Reader`(jOOQ `adapter.query`, 읽기 전용 TX)에
 * 위임한다 — web adapter는 이 Use Case만 부른다(r-2, ArchUnit `webAdapterUsesOnlyApplication`). 권한·감사·조회 창은 호출자(ADMIN — 서명 채널 뒤, BIZ-3)가 맡는다.
 *
 * 노출(§9, D-BIZ-006 ②): 사업자등록번호는 **어디서나 마스킹 값만**(SQL에서 마스킹 — 평문이 메모리로 오지 않는다). 목록·번호 조회·중복 요약에는 개인정보가 없고(대표자는 마스킹
 * 이름만), 상세만 대표자·담당자 이름·전화·이메일·주소·법인등록번호 원문을 담는다. 이력은 저장된 마스킹 값 그대로다.
 */
interface BusinessProfileQueryUseCase {
    /** 목록·검색(§6.2 — `business_id` 내림차순 keyset). 가맹점 0개 사업자도 나온다. */
    fun search(filter: BusinessSearchFilter): BusinessDirectoryPage

    /** 번호 정확 일치(§6.3 — 합친 MERCHANT-BIZ-LOOKUP-001, UNIQUE라 0/1). 찾음·못 찾음 같은 모양(목록 길이만 다르다). */
    fun lookup(number: BusinessRegistrationNumber): List<BusinessSummary>

    /** 상세(§6.4 — 현재 행만). 없으면 null. */
    fun detail(businessId: BusinessId): BusinessDetailView?

    /** 변경 이력 한 쪽(§6.5 — `afterSeq` 초과 오름차순, [BusinessHistoryPage.PAGE_SIZE]행). 사업자가 없으면 null. */
    fun history(businessId: BusinessId, afterSeq: Long): BusinessHistoryPage?

    /**
     * 중복 거절의 기존 사업자 요약(§6.7 D-BIZ-003·C-14 — 저장된 거절 reference로 TX 밖에서 읽는다). 없으면 null(삭제 경로가 없으므로 결함 — 호출자는 503, m-1).
     */
    fun existingSummary(businessId: BusinessId): BusinessSummary?
}

/**
 * 목록 검색 조건(§6.2 — 모두 선택). [displayNamePrefix]는 상호 **앞부분** 일치(C-5), [createdFrom]·[createdTo]는 KST 달력 날짜(양끝 포함 — [createdAtFrom]·[createdAtBefore]가
 * Asia/Seoul 00:00 경계의 순간). 번호 필드는 없다(번호 검색은 [BusinessProfileQueryUseCase.lookup]만 — C-6). keyset: [after] 미만, `limit` 1..[MAX_LIMIT].
 */
data class BusinessSearchFilter(
    val displayNamePrefix: DisplayName? = null,
    val businessType: BusinessType? = null,
    val registrationStatus: BusinessRegistrationStatus? = null,
    val createdFrom: LocalDate? = null,
    val createdTo: LocalDate? = null,
    val after: BusinessId? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be within 1..$MAX_LIMIT" }
        require(createdFrom == null || createdTo == null || !createdFrom.isAfter(createdTo)) { "createdFrom must not be after createdTo" }
    }

    /** 포함 하한 = createdFrom의 KST 00:00. */
    val createdAtFrom: Instant? get() = createdFrom?.atStartOfDay(KST)?.toInstant()

    /** 제외 상한 = createdTo 다음 날의 KST 00:00(그 날 끝 포함). */
    val createdAtBefore: Instant? get() = createdTo?.plusDays(1)?.atStartOfDay(KST)?.toInstant()

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/** 목록 한 쪽. [next] = 다음 쪽이 있을 때 마지막 항목의 businessId. */
data class BusinessDirectoryPage(val items: List<BusinessDirectoryItem>, val next: String?)

/** 목록 항목(§6.2). 원문 번호·대표자 원문·연락처 없음 — [representativeNameMasked]는 현재 첫 순서 대표자의 마스킹 이름(없으면 null). */
data class BusinessDirectoryItem(
    val businessId: String,
    val businessName: String,
    val registrationNumberMasked: String,
    val businessType: String?,
    val registrationStatus: String,
    val verificationStatus: String,
    val representativeNameMasked: String?,
    val representativeCount: Long,
    val merchantCount: Long,
    val updatedAt: Instant,
)

/** 번호 조회 항목·중복 요약(§6.3·§6.7 — 상호·마스킹 번호·내부 키(화면 미표시 — D-BIZLOOKUP-001)·등록 상태(D-BIZ-006 ③)). */
data class BusinessSummary(
    val businessId: String,
    val businessName: String,
    val registrationNumberMasked: String,
    val registrationStatus: String,
)
