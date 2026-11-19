package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.MerchantCode
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
 * 목록 검색 조건([displayNamePrefix] 외에는 모두 정확 일치, 없으면 조건 없음 — 조건끼리는 AND). [registrationNumber]는 형식 검사를 마친 값 객체로만 받는다(toString은 마스킹).
 * keyset: merchant_id 오름차순, [after] 초과. [limit] 1..[MAX_LIMIT].
 */
data class MerchantSearchFilter(
    val merchantId: MerchantId? = null,
    val businessId: BusinessId? = null,
    val lifecycle: Lifecycle? = null,
    val registrationNumber: BusinessRegistrationNumber? = null,
    val after: MerchantId? = null,
    val limit: Int = DEFAULT_LIMIT,
    /**
     * MERCHANT-CODE-001 §3.8: 코드 검색(정확 일치, **현재·은퇴 코드 모두**). 단일 기준 — 다른 필터·cursor와 함께 쓸 수 없다(parser 400, 여기서도 불변식).
     * 결과 항목은 `matchedRetiredCode`를 갖는 별도 응답 모양이다.
     */
    val merchantCode: MerchantCode? = null,
    /**
     * MERCHANT-NAME-SEARCH-001: 가맹점명(`merchant.display_name`) **앞부분 일치**(글자 그대로 — `%`·`_`는 와일드카드가 아니다, 대소문자 구분 utf8mb4_0900_bin, 정규화 없음).
     * 형식은 [DisplayName] 규칙(1~100 code point). 다른 조건과 AND, [merchantCode]와는 함께 쓸 수 없다.
     */
    val displayNamePrefix: DisplayName? = null,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be within 1..$MAX_LIMIT" }
        require(merchantCode == null || listOf(merchantId, businessId, lifecycle, registrationNumber, after, displayNamePrefix).all { it == null }) {
            "merchantCode is a single search criterion"
        }
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
    /** MERCHANT-CODE-001 §3.8: 현재 코드(가장 큰 seq). */
    val merchantCode: String,
    /** 코드 검색에서만 값이 있다 — 검색한 코드가 은퇴 코드면 true(현재 코드면 false). 목록·기존 검색 응답에는 키 자체가 없다. */
    val matchedRetiredCode: Boolean? = null,
)
