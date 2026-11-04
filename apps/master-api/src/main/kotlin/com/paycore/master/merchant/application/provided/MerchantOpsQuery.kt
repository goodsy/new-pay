package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.MerchantId
import java.time.Instant

/**
 * 운영 화면용 가맹점 조회(MERCHANT-OPS-001 §3 — CQRS 조회 측, jOOQ `adapter.query`가 DTO로 읽는다, 읽기 전용). 판정용 권위 조회가 아니다.
 * 권한·감사는 호출자(ADMIN — 서명 채널 뒤)가 맡는다. **사업자등록번호 평문·기관 가맹점 번호(`institutionMerchantRef`)·변경 이력 detail은 어떤 view에도 없다**(D-MD-008, D-MS2-005).
 */
interface MerchantOpsQuery {
    fun search(filter: MerchantSearchFilter): MerchantDirectoryPage

    fun detail(merchantId: MerchantId): MerchantDetailView?

    /** 가맹점이 없으면 null. [afterSeq] 다음 이력(오름차순, 한 쪽 [MerchantHistoryPage.PAGE_SIZE]행). */
    fun history(merchantId: MerchantId, afterSeq: Long): MerchantHistoryPage?
}

/**
 * 목록 검색 조건(모두 정확 일치, 없으면 조건 없음). [registrationNumber]는 형식 검사를 마친 값 객체로만 받는다(toString은 마스킹).
 * keyset: merchant_id 오름차순, [after] 초과. [limit] 1..[MAX_LIMIT].
 */
data class MerchantSearchFilter(
    val merchantId: MerchantId? = null,
    val businessId: BusinessId? = null,
    val lifecycle: Lifecycle? = null,
    val registrationNumber: BusinessRegistrationNumber? = null,
    val after: MerchantId? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be within 1..$MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

/** 목록 한 쪽. [nextCursor]는 다음 쪽이 있을 때 마지막 항목의 merchantId. 찾음/못 찾음 모양이 같다(항목 수만 다르다 — oracle 방어 §5.5). */
data class MerchantDirectoryPage(val items: List<MerchantDirectoryItem>, val nextCursor: String?)

data class MerchantDirectoryItem(
    val merchantId: String,
    val merchantName: String,
    val businessId: String,
    val businessName: String,
    val registrationNumberMasked: String,
    val lifecycle: String,
    val entitlementRevision: Long,
    val midCount: Long,
    val createdAt: Instant,
)
