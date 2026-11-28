package com.paycore.admin.merchantops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 화면 사업자 조회(BUSINESS-PROFILE-001 §7.1·§7.2 — BIZ-3). 판정 = 계정 현재 행 ACTIVE + 업무 조회 역할(앞단 — 아니면 403, 감사 없음) → 사업자 조회 권한
 * (`BusinessPermissions.VIEW` = OPERATOR·APPROVER — D-BIZ-006 ①) → 사업자 조회 창(`business-reads-per-minute` — 목록·상세·이력·번호 조회 공용, m-2) → MASTER(DB TX 밖).
 * - 목록·이력: 감사 없음(D-MOPS-002), 창 초과는 지표만.
 * - 상세: 열람마다 감사 `BUSINESS_VIEWED`(D-BIZ-006 ② — 개인정보 원문을 돌려주므로 **반환 전** 기록, 실패하면 결과를 돌려주지 않는다), 역할 부족·창 초과도 감사.
 * 결과는 값([BusinessQueryResult]). 입력 원문·개인정보를 로그·감사에 남기지 않는다.
 */
interface BusinessQueryUseCase {
    fun search(actorId: String, criteria: BusinessSearchCriteria, context: AdminRequestContext): BusinessQueryResult

    /** 검색 본문이 엄격 해석에 실패했다(본문 원문은 받지 않는다). 앞단 → 권한 순서로 403을 먼저 판정하고, 통과하면 InvalidInput(감사 없음 — 목록은 감사 대상이 아니다). */
    fun rejectMalformedSearch(actorId: String): BusinessQueryResult

    fun detail(actorId: String, businessId: String, context: AdminRequestContext): BusinessQueryResult

    fun history(actorId: String, businessId: String, afterSeq: Long?): BusinessQueryResult

    /**
     * BIZ-CRIT-APPROVAL-001 R3-m2: 1인 경로 선택 근거 원문 1건(`historyId` = 변경 이력 행). 사업자 조회 권한 → 조회 창 → MASTER(사업자 범위 — 다른 사업자 이력은 404) → **APPROVER 또는 그 저장의 요청자**
     * (MASTER 응답 `recordedBy` = 세션 계정)만 200, 아니면 403. 조회마다 감사 `BUSINESS_CHANGE_EVIDENCE_VIEWED`(값 없음 — 반환 전).
     */
    fun evidence(actorId: String, businessId: String, historyId: Long, context: AdminRequestContext): BusinessQueryResult
}

/**
 * 사업자 목록 검색 조건(§6.2 — 화면 입력 원문, 형식 검사는 Use Case가 MASTER 호출 전에 한다). 모든 필드 선택이며 **null = 보내지 않음**(MASTER parser는 필드 생략만 허용 —
 * JSON null을 보내지 않는다, BIZ-2 인계 ④). 사업자등록번호 필드는 없다(번호 검색은 번호 조회만 — C-6). [displayNamePrefix]는 toString에 나오지 않는다(개인사업자 상호에 이름이 있을 수 있다 — L-3).
 */
data class BusinessSearchCriteria(
    val displayNamePrefix: String? = null,
    val businessType: String? = null,
    val registrationStatus: String? = null,
    val createdFrom: String? = null,
    val createdTo: String? = null,
    val after: String? = null,
    val limit: Int? = null,
) {
    override fun toString(): String =
        "BusinessSearchCriteria(displayNamePrefix=${if (displayNamePrefix == null) "null" else "***"}, businessType=$businessType, registrationStatus=$registrationStatus, " +
            "createdFrom=$createdFrom, createdTo=$createdTo, after=$after, limit=$limit)"
}

/** 사업자 조회·번호 조회 결과(§7.3). [Found]의 문서는 MASTER 응답을 허용 모양으로 검사해 다시 만든 값이다(화면은 `businessId`를 그리지 않는다). */
sealed interface BusinessQueryResult {
    data class Found(val document: Map<String, Any?>) : BusinessQueryResult {
        /** BIZ-3 리뷰 NIT-5: 상세 문서에 개인정보 원문(대표자·담당자·연락처·주소·법인번호)이 있다 — toString은 값을 가린다(로그·예외 비노출). */
        override fun toString(): String = "Found(***)"
    }

    data object InvalidInput : BusinessQueryResult

    data object Forbidden : BusinessQueryResult

    data object NotFound : BusinessQueryResult

    data object RateLimited : BusinessQueryResult

    data object AuditUnavailable : BusinessQueryResult

    data class Unavailable(val reason: String) : BusinessQueryResult
}
