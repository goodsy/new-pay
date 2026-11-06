package com.paycore.admin.financialops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 화면용 매입 현황 조회(ACQ-VIEW-001 §4.6 — 읽기 전용). 조회자는 요청마다 계정 현재 행(ACTIVE + VIEW_MASKED 4역할 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN — D-ACQV-001)으로 판정한다.
 * - 목록: 감사 없음(D-ACQV-001).
 * - 파일 상세·레코드 쪽: FINANCIAL이 답하면(FOUND·NOT_FOUND) `ACQUISITION_FILE_VIEWED` 감사 1행(targetRef = fileId)을 **결과를 돌려주기 전에** 쓰고, 감사 실패면 결과를 돌려주지 않는다.
 * FINANCIAL 호출은 DB TX 밖, financialops 전용 permit(PAYMENT·MASTER 운영과 격리). 실패는 결과 값([AcquisitionLookupResult]).
 */
interface AcquisitionLookupUseCase {
    fun search(viewerActorId: String, criteria: AcquisitionFileSearchCriteria): AcquisitionLookupResult

    /**
     * 엄격 해석에 실패한 검색·레코드 검색 본문(원문은 받지 않는다 — TXN `rejectMalformedSearch`와 같은 규칙, B2 리뷰 MINOR-2): 조회 역할이 없거나 계정이 ACTIVE가 아니면 Forbidden, 있으면
     * InvalidInput. 감사 없음, FINANCIAL 미호출, 속도 제한 계수 없음.
     */
    fun rejectMalformedSearch(viewerActorId: String): AcquisitionLookupResult

    fun detail(viewerActorId: String, fileId: String, context: AdminRequestContext): AcquisitionLookupResult

    fun records(viewerActorId: String, fileId: String, criteria: AcquisitionRecordSearchCriteria, context: AdminRequestContext): AcquisitionLookupResult
}

/** 목록 keyset 위치(FINANCIAL 계약 §3.3 — 업무일 `yyyy-MM-dd`, 파일 ID). */
data class AcquisitionFileCursor(val businessDate: String, val fileId: String)

/**
 * 파일 검색 조건(화면 입력 — 형식·모드·기간 검사는 Use Case가 FINANCIAL 호출 **전에** 한다, FINANCIAL이 다시 검사한다). 키 모양은 FINANCIAL 검색 본문(§3.3)과 같다.
 */
data class AcquisitionFileSearchCriteria(
    val businessDateFrom: String? = null,
    val businessDateTo: String? = null,
    val fileId: String? = null,
    val acquirerCode: String? = null,
    val environment: String? = null,
    val transmissionState: String? = null,
    val receiptState: String? = null,
    val resultState: String? = null,
    val needsAttention: Boolean? = null,
    val after: AcquisitionFileCursor? = null,
    val limit: Int? = null,
)

/** 레코드 쪽 조건(§3.3 — 현재 상태 필터, `seq > afterSeq`, limit 1..50). */
data class AcquisitionRecordSearchCriteria(val status: String? = null, val afterSeq: Int? = null, val limit: Int? = null)

sealed interface AcquisitionLookupResult {
    /** FINANCIAL 응답을 허용 필드 모양으로 검사해 다시 만든 문서(문자열·숫자·불린·null·목록·객체만). */
    data class Found(val document: Map<String, Any?>) : AcquisitionLookupResult

    data object InvalidInput : AcquisitionLookupResult

    data object Forbidden : AcquisitionLookupResult

    data object NotFound : AcquisitionLookupResult

    data object RateLimited : AcquisitionLookupResult

    data object AuditUnavailable : AcquisitionLookupResult

    data class Unavailable(val reason: String) : AcquisitionLookupResult
}
