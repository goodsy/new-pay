package com.paycore.master.merchant.domain

/**
 * 사업자 하위 정보 묶음(수정 단위 — 명세 §4.1). **현재 행만** 담는다(종료된 대표자 등 이력은 조회 Reader가 읽는다 — 무제한 컬렉션 회피).
 * 목록 순서 = 입력 순서(대표자·담당자는 목록 위치, 업태/종목은 유형 안 위치, 주소는 유형으로 구별). 같은 내용·다른 순서는 다른 profile이다(C-10).
 * 규칙 판정은 [BusinessProfileRules]가 한다(생성자는 형태만 — 해석기가 모든 위반을 모아 돌려줄 수 있게).
 */
data class BusinessProfile(
    val registration: BusinessRegistration,
    val representatives: List<BusinessRepresentative>,
    val categories: List<BusinessCategory>,
    val addresses: List<BusinessAddress>,
    val contacts: List<BusinessContact>,
) {
    /**
     * 저장·비교용 정규 형태: 업태 → 종목 순으로 모으고(유형 안 순서 유지 — DB `display_order`가 유형별이라 유형 사이 끼워 넣기 순서는 저장되지 않는다),
     * 주소는 REGISTERED → ACTUAL 순. 대표자·담당자 순서는 그대로. 지문(멱등)은 정규화 전 요청 순서를 쓴다.
     */
    fun canonical(): BusinessProfile = copy(
        categories = categories.sortedBy { it.type.ordinal },
        addresses = addresses.sortedBy { it.type.ordinal },
    )

    /** 유형 안 1부터의 순서와 함께(저장·짝짓기 키 — §4.1 하위 행 동일성 = 내용 + 순서). */
    fun orderedCategories(): List<Pair<BusinessCategory, Int>> =
        canonical().categories.groupBy { it.type }.values.flatMap { same -> same.mapIndexed { i, c -> c to i + 1 } }

    companion object {
        val EMPTY = BusinessProfile(BusinessRegistration.EMPTY, emptyList(), emptyList(), emptyList(), emptyList())
    }
}