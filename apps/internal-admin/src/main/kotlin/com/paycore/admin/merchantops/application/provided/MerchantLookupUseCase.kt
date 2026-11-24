package com.paycore.admin.merchantops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 화면용 가맹점 조회(MERCHANT-OPS-001 §4.1·§5). 조회자는 요청마다 계정 현재 행(ACTIVE + 조회 역할 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN)으로 판정한다.
 * - 목록: 감사 없음. 단 **사업자등록번호 검색은 OPERATOR·APPROVER만, 모든 시도 감사**(`MERCHANT_BRN_SEARCHED` — 평문 없음, §5.2·§5.4), 등록번호 전용 속도 제한.
 * - 상세: MASTER가 답하면 감사 1행(`MERCHANT_VIEWED`), 감사 실패 시 결과 미반환.
 * - 이력: 감사 없음.
 * MASTER 호출은 DB TX 밖, merchantops 전용 permit(PAYMENT 운영과 격리). 실패는 결과 값([MerchantLookupResult]).
 */
interface MerchantLookupUseCase {
    fun search(viewerActorId: String, criteria: MerchantSearchCriteria, context: AdminRequestContext): MerchantLookupResult

    /**
     * 검색 본문이 엄격 해석에 실패했다(§5.2, Q-MOPS-07). 본문 원문은 받지 않는다(감사·로그·응답 비노출). [registrationNumberAttempt]는 adapter가 판정한 "등록번호 검색 시도" 여부다.
     * 계정 ACTIVE + 조회 역할(아니면 Forbidden, 감사 없음) → 시도가 아니면 InvalidInput(감사 없음) → 시도면 §5.2 순서: 등록번호 역할(아니면 Forbidden + 감사 `FORBIDDEN`)
     * → InvalidInput + 감사 `INVALID_INPUT`, 둘 다 detail `brn=INVALID`. 속도 제한 앞이다(형식 위반). 감사 실패 → AuditUnavailable. MASTER는 부르지 않는다.
     */
    fun rejectMalformedSearch(viewerActorId: String, registrationNumberAttempt: Boolean, context: AdminRequestContext): MerchantLookupResult

    fun detail(viewerActorId: String, merchantId: String, context: AdminRequestContext): MerchantLookupResult

    fun history(viewerActorId: String, merchantId: String, afterSeq: Long?): MerchantLookupResult
}

/**
 * 검색 조건(화면 입력 원문 — 형식 검사는 Use Case가 MASTER 호출 전에 한다). [registrationNumber]는 toString에 나오지 않는다(로그·예외 비노출).
 */
data class MerchantSearchCriteria(
    val merchantId: String? = null,
    val businessId: String? = null,
    val lifecycle: String? = null,
    val registrationNumber: String? = null,
    val after: String? = null,
    val limit: Int? = null,
    /** MERCHANT-CODE-001 MC-2 코드 검색(§3.8 — 정확 일치, 현재·은퇴 코드). 단일 기준: `limit`만 함께 올 수 있다(형식·조합 검사는 Use Case). 공개값이라 toString에 그대로 둔다. */
    val merchantCode: String? = null,
    /**
     * MERCHANT-NAME-SEARCH-001 MNS-2 가맹점명 앞부분(§3 — MASTER `DisplayName` 규칙, 정규화 없음). 다른 필터와 AND, `merchantCode`와 함께면 400(형식·조합 검사는 Use Case).
     * toString에 나오지 않는다(`***` — 개인사업자 상호에 사람 이름이 있을 수 있다, BUSINESS-PROFILE-001 L-3 취지·[BusinessSearchCriteria]와 같다).
     */
    val displayNamePrefix: String? = null,
) {
    override fun toString(): String =
        "MerchantSearchCriteria(merchantId=$merchantId, businessId=$businessId, lifecycle=$lifecycle, registrationNumber=${if (registrationNumber == null) "null" else "****"}, after=$after, limit=$limit, " +
            "merchantCode=$merchantCode, displayNamePrefix=${if (displayNamePrefix == null) "null" else "***"})"
}

sealed interface MerchantLookupResult {
    /** MASTER 응답을 허용 필드 모양으로 검사해 다시 만든 문서(문자열·숫자·불린·null·목록·객체만). */
    data class Found(val document: Map<String, Any?>) : MerchantLookupResult {
        /** BRN-UNMASK-001 M-2(D-BIZ-007): 목록·상세 문서에 사업자등록번호 원문이 있다 — toString은 값을 가린다(로그·예외 비노출, [BusinessQueryResult.Found]와 같은 규칙). */
        override fun toString(): String = "Found(***)"
    }

    data object InvalidInput : MerchantLookupResult

    data object Forbidden : MerchantLookupResult

    data object NotFound : MerchantLookupResult

    data object RateLimited : MerchantLookupResult

    data object AuditUnavailable : MerchantLookupResult

    data class Unavailable(val reason: String) : MerchantLookupResult
}
