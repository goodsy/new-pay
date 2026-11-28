package com.paycore.admin.merchantops.application.provided

/**
 * MASTER 사업자 중요정보 변경 채널(BIZ-CRIT-APPROVAL-001 §5.1 — approval 모듈의 [com.paycore.admin.approval.application.BusinessChangeApprovalService]가 쓴다, 의존 방향 approval → merchantops).
 * 모두 **DB TX 밖**에서 부른다(gateway가 TX 안이면 거부). 실행·기한 확인·결정 조회는 기존 [MerchantApprovalClient](operation [MerchantApprovalOperation.APPLY_BUSINESS_CRITICAL_CHANGE])를 쓴다.
 *
 * - [propose]: 입력 값 규칙(사유 필수·근거 참조 형식·카드번호 모양·내용 규칙 — `BusinessProfileInputRules`) → MASTER 제안 명령(쓰기 permit, 키 `mo1.<계정>.<submissionId>`).
 * - [approvalState]: 승인 A 단계(m-1 — version·생명주기·등록 상태만).
 * - [original]: 변경 후 원문(M-1 — 호출자 역할·미해결 상태 판단은 approval 서비스가 먼저 한다).
 */
interface BusinessChangeClient {
    fun propose(request: BusinessChangeProposalRequest): BusinessChangeProposalResult

    fun approvalState(businessId: String): BusinessChangeRead

    /** [actorId]의 사업자 조회 창(D-BIZ-006 ② — 상세 원문 조회와 같은 창·한도)을 1회 쓴다. 남은 횟수가 없으면 [BusinessChangeRead.RateLimited](MASTER 미호출). */
    fun original(actorId: String, businessId: String, proposalId: String): BusinessChangeRead

    /** 승인 요청 입력 값 규칙(MASTER 호출 전 — §7.2 순서 ①): null = 식별자 형식 위반(필드 오류 없는 400), 빈 목록 = 통과. */
    fun inputErrors(request: BusinessChangeProposalRequest): List<BusinessFieldError>?
}

/** 제안 입력(수정 본문 + version + 사유 + 근거 참조 + 요청자 — §5.2). [actorId] = 세션 계정(`adm_<ULID>`). toString 비노출. */
class BusinessChangeProposalRequest(
    val actorId: String,
    val submissionId: String,
    val businessId: String,
    val version: Long,
    val reason: String?,
    val evidenceRef: String,
    val content: BusinessProfileContent,
) {
    override fun toString(): String = "BusinessChangeProposalRequest(businessId=$businessId, version=$version, ***)"
}

/** 제안 결과(값). [Proposed]는 MASTER 제안 응답 요약(상호·개인정보 없음 — M-5). */
sealed interface BusinessChangeProposalResult {
    @Suppress("LongParameterList")
    class Proposed(
        val proposalId: String,
        val baseVersion: Long,
        val contentDigest: String,
        val changedFields: List<String>,
        val representativeChanged: Boolean,
        val subjectCorrection: Boolean,
        val requestedBy: String,
        val replayed: Boolean,
    ) : BusinessChangeProposalResult {
        override fun toString(): String = "Proposed(proposalId=$proposalId, fields=$changedFields, replayed=$replayed)"
    }

    /** MASTER 저장 거절(예: `BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED`·`BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`·`BUSINESS_VERSION_CONFLICT`). */
    data class Rejected(val rejection: String, val replayed: Boolean) : BusinessChangeProposalResult

    /** 입력 값 규칙 위반(ADMIN 판정 또는 MASTER 400 — 효과 없음 확정). */
    data class Invalid(val fieldErrors: List<BusinessFieldError>) : BusinessChangeProposalResult

    data object KeyReused : BusinessChangeProposalResult

    data class Busy(val reason: String) : BusinessChangeProposalResult

    /** MASTER가 확정하지 못했다 — 같은 submissionId로 다시. */
    data class RetrySameKey(val reason: String) : BusinessChangeProposalResult

    /** 효과 유무 불명(timeout·해석 불가 등) — 같은 submissionId로 다시. */
    data class Unknown(val reason: String) : BusinessChangeProposalResult
}

/** MASTER 조회 결과(엄격 모양 검사 뒤 값). */
sealed interface BusinessChangeRead {
    data class Found(val document: Map<String, Any?>) : BusinessChangeRead {
        override fun toString(): String = "Found(***)"
    }

    data object NotFound : BusinessChangeRead

    data object RateLimited : BusinessChangeRead

    data class Unavailable(val reason: String) : BusinessChangeRead
}

/**
 * 사업자 상세의 중요정보 변경 승인 대기 표시·1인 수정 차단(§3.4·§5.2 — 구현은 approval 모듈, 가맹점 [MerchantPendingApprovals] 선례). merchantops는 구현을 모른다.
 * - [pendingFor]: 사업자 조회 권한(OPERATOR·APPROVER) 보유 조회자에게만 {상태, 요청 시각}, 아니면 null(m-2). 내용·요청자 없음.
 * - [hasUnresolved]: 1인 수정·등록 완료 전 차단 판정(조회자 무관 — MASTER 호출 전 409 `BUSINESS_CHANGE_PENDING`, advisory: 검사-호출 사이 경합은 MASTER version이 막는다).
 */
interface BusinessPendingApprovals {
    fun pendingFor(viewerActorId: String, businessId: String): PendingBusinessChange?

    fun hasUnresolved(businessId: String): Boolean
}

data class PendingBusinessChange(val status: String, val requestedAt: String)

/**
 * 사업자 중요정보 변경 **승인 요청** SPI(§5.2 — merchantops 화면 API `POST /api/businesses/{biz}/critical-change-requests`가 부르고 approval 모듈이 구현, [BusinessPendingApprovals]와 같은 방향).
 * [request]의 [BusinessProfileRequest.Change.evidenceRef]는 필수(P-4), 사유도 필수(2인 경로 입력 규칙).
 */
fun interface BusinessChangeRequests {
    fun request(actorId: String, sessionGeneration: Long, request: BusinessProfileRequest.Change, context: com.paycore.admin.account.application.provided.AdminRequestContext): BusinessChangeRequestOutcome
}

/** 승인 요청 결과(화면 매핑: Created 201, Invalid 400, Conflict 409, Denied 403, SessionReplaced 401, Unavailable·RetrySameKey·OutcomeUnknown 503, IntegrityViolation 500). */
sealed interface BusinessChangeRequestOutcome {
    data class Created(val approvalId: String) : BusinessChangeRequestOutcome

    data class Invalid(val fieldErrors: List<BusinessFieldError>) : BusinessChangeRequestOutcome

    /** 업무 충돌(`DUPLICATE_UNRESOLVED`·MASTER 저장 거절 code — `BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED`·`BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`·`BUSINESS_VERSION_CONFLICT` 등·`IDEMPOTENCY_KEY_REUSED`). */
    data class Conflict(val code: String) : BusinessChangeRequestOutcome

    data object Denied : BusinessChangeRequestOutcome

    data object SessionReplaced : BusinessChangeRequestOutcome

    data object NotFound : BusinessChangeRequestOutcome

    /** MASTER·permit 장애 또는 결과 불명 — 같은 submissionId로 다시(새 제안이 생기지 않는다 — MASTER 멱등). */
    data class Unavailable(val code: String) : BusinessChangeRequestOutcome

    data object RetryLater : BusinessChangeRequestOutcome

    data object IntegrityViolation : BusinessChangeRequestOutcome
}
