package com.paycore.admin.merchantops.application.provided

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * MASTER 2인 승인 명령 채널 클라이언트(MERCHANT-OPS-002 B2-2 §9.4·§9.5 — 서명 v1, 조회 키 하나 D-MOPS-005, **DB TX 밖에서만**: TX 안이면 `IllegalStateException`). 호출자는 approval의
 * 지정 서비스뿐(ArchitectureSpec).
 * - [revisionTarget]: 승인 요청·승인 A 단계의 MASTER 상세 재조회(조회 permit — 비권위, 적격 판정의 권위는 MASTER 실행 TX).
 * - [execute]·[confirmDeadline]: 승인 TX1이 저장한 바이트 그대로 POST(쓰기 permit, 재전송도 같은 바이트 — 멱등은 MASTER `admin_request` (operation, `mop1.<approvalId>`)).
 * - [decision]: 서명된 결정 조회(조회 permit, 쓰기 효과 없음). NONE은 효과 없음의 근거가 아니다.
 * 응답 매핑(C-28)은 [MerchantApprovalResult] 참고.
 */
interface MerchantApprovalClient {
    fun revisionTarget(merchantId: String, contractId: String, revisionNo: Int): RevisionTargetResult

    /** B2-3 첫 MID 발급 대상 사실(MASTER 상세 — MID 수·Revision 승인·CSA·PRIMARY Site). 적격 판정은 호출자(advisory), 권위는 MASTER 실행 TX. */
    fun firstMidTarget(merchantId: String, contractId: String, agreementId: String, revisionNo: Int, primarySiteId: String): FirstMidTargetResult

    /** B3-1 결제수단 개통 승인 대상 사실(MASTER 상세 — MID·개통 행·[now]에 열린 PRIMARY Site). 적격 판정은 호출자(advisory), 권위는 MASTER 실행 TX. */
    fun activationTarget(merchantId: String, merchantAccountId: String, activationId: String, now: Instant): MerchantTargetResult<ActivationTarget>

    /** B3-1 Site 심사 승인 대상 사실(MASTER 상세 — Site 생애·심사 상태·등록자). */
    fun siteReviewTarget(merchantId: String, merchantSiteId: String): MerchantTargetResult<SiteReviewTarget>

    /** B3-2 통화 활성화 대상 사실(MASTER 상세 — MID 생애·[now]에 끝나지 않은 같은 통화 기록 유무). 적격 판정은 호출자(advisory), 권위는 MASTER 실행 TX. */
    fun currencyTarget(merchantId: String, merchantAccountId: String, currency: String, now: Instant): MerchantTargetResult<CurrencyTarget>

    /** MERCHANT-CODE-001 MC-1b 코드 변경 대상 사실(MASTER 상세 — 현재 코드·변경 횟수·`merchantCodeChangeable` advisory). 권위는 MASTER 실행 TX(§3.7.3). */
    fun codeChangeTarget(merchantId: String): MerchantTargetResult<CodeChangeTarget>

    fun execute(operation: MerchantApprovalOperation, body: ByteArray): MerchantApprovalResult

    fun confirmDeadline(operation: MerchantApprovalOperation, body: ByteArray): MerchantApprovalResult

    fun decision(approvalId: String): MerchantApprovalResult
}

/** MASTER 승인 명령(경로·operation code — 성공 reference 문법은 응답 모양 표가 종류별로 검사). B2-3에서 첫 MID 발급을 더한다. */
enum class MerchantApprovalOperation(val path: String, val code: String) {
    APPROVE_CONTRACT_REVISION("contract-revision-approvals", "ApproveContractRevision"),

    /** B2-3 첫 Default MID 발급(성공 reference `mid_…`). */
    ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT("first-default-merchant-accounts", "IssueFirstDefaultMerchantAccount"),

    /** B3-1 결제수단 개통 승인(성공 reference = 그 `act_…`, 본문 `operation` 필드 — C-39). */
    APPROVE_PAYMENT_METHOD_ACTIVATION("payment-method-activation-approvals", "ApprovePaymentMethodActivation"),

    /** B3-1 Site 심사 승인(APPROVED 고정, 성공 reference `site_…#<seq>`). */
    DECIDE_SITE_REVIEW("site-review-approvals", "DecideSiteReview"),

    /** B3-2 통화 활성화(효력 시작 `AT_EXECUTION`/KST 00:00 — D-MOPS-009 ②, 성공 reference `cur_…`). */
    ENABLE_CURRENCY("currency-enablements", "EnableCurrency"),

    /** MERCHANT-CODE-001 MC-1b 가맹점 코드 1회 변경(성공 reference `mer_…`). */
    CHANGE_MERCHANT_CODE("merchant-code-changes", "ChangeMerchantCode"),
}

/**
 * 코드 변경 대상 사실(MC-1b §5.3·§5.5). [changeable] = MASTER 상세 `merchantCodeChangeable`(seq 2 없음 ∧ 계약 승인 사실 없음 ∧ ACTIVE — advisory),
 * [changeCount] = 코드 이력 행 수 − 1(0 또는 1).
 */
data class CodeChangeTarget(val merchantId: String, val merchantName: String, val currentCode: String, val changeable: Boolean, val changeCount: Int)

/** B3 승인 대상 조회 결과(MASTER 상세 한 번의 읽기). */
sealed interface MerchantTargetResult<out T> {
    data class Found<T>(val target: T) : MerchantTargetResult<T>

    /** MASTER 404 `MERCHANT_NOT_FOUND`. */
    data object MerchantNotFound : MerchantTargetResult<Nothing>

    data class Unavailable(val reason: String) : MerchantTargetResult<Nothing>
}

/**
 * 결제수단 개통 승인 대상 사실(B3-1 §9.16). MID가 상세에 없으면 [accountLifecycle] null, 개통 행이 없으면 [status]·[paymentMethod]·[registeredBy] null.
 * [primarySiteId] = 조회 시각에 열린 PRIMARY Assignment의 Site(없거나 둘 이상이면 null — C-45: 승인자가 본 PRIMARY를 본문에 고정한다). [registeredBy]는 표시 + 승인 TX1 등록자 403 근거(D-MOPS-009 ③).
 */
data class ActivationTarget(
    val merchantId: String,
    val merchantName: String,
    val merchantAccountId: String,
    val accountLifecycle: String?,
    val activationId: String,
    val paymentMethod: String?,
    val status: String?,
    val registeredBy: String?,
    val primarySiteId: String?,
    /** MERCHANT-CODE-001 MC-3a(§5.1·§5.3): MASTER 상세 `merchant.merchantCode`(요청 시점 현재 코드 — 승인 행 표기·pinned 표시값). */
    val merchantCode: String,
)

/**
 * 통화 활성화 대상 사실(B3-2 §9.16). MID가 상세에 없으면 [accountLifecycle] null. [openRecord] = 같은 통화 기록 중 [종료 시각 없음 또는 조회 시각 이후 종료]가 있음
 * (ADMIN 미리 보기의 "같은 통화 열린 기록" — 중첩 판정의 권위는 MASTER `CURRENCY_ENABLEMENT_OVERLAP`).
 */
data class CurrencyTarget(
    val merchantId: String,
    val merchantName: String,
    val merchantAccountId: String,
    val accountLifecycle: String?,
    val currency: String,
    val openRecord: Boolean,
    /** MERCHANT-CODE-001 MC-3a(§5.1·§5.3): MASTER 상세 `merchant.merchantCode`(요청 시점 현재 코드 — 승인 행 표기·pinned 표시값). */
    val merchantCode: String,
)

/** Site 심사 승인 대상 사실(B3-1 §9.16). Site가 없으면 [siteLifecycle] null. [reviewStatus] = 심사 결정(결정 없음 = `PENDING`). 채널 식별값 없음. */
data class SiteReviewTarget(
    val merchantId: String,
    val merchantName: String,
    val merchantSiteId: String,
    val siteLifecycle: String?,
    val channelType: String?,
    val reviewStatus: String?,
    val registeredBy: String?,
    /** MERCHANT-CODE-001 MC-3a(§5.1·§5.3): MASTER 상세 `merchant.merchantCode`(요청 시점 현재 코드 — 승인 행 표기·pinned 표시값). */
    val merchantCode: String,
)

/**
 * 승인 명령의 MASTER 상세 기준 대상(가맹점·계약·Revision 한 줄). [registeredBy] = Revision 등록자(정규 actor — D-MOPS-008 ③). [ended] = MASTER 상세의 종료 시각이 조회 시각 이전.
 * 사업자등록번호·채널 식별값은 없다.
 */
data class ContractRevisionTarget(
    val merchantId: String,
    val merchantName: String,
    val contractId: String,
    val revisionNo: Int,
    val scopeKind: String,
    val scopeSiteIds: List<String>,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val approved: Boolean,
    val registeredBy: String,
    /** MERCHANT-CODE-001 MC-3a(§5.1·§5.3): MASTER 상세 `merchant.merchantCode`(요청 시점 현재 코드 — 승인 행 표기·pinned 표시값). */
    val merchantCode: String,
) {
    fun endedAt(now: Instant): Boolean = effectiveTo != null && !now.isBefore(effectiveTo)
}

sealed interface RevisionTargetResult {
    data class Found(val target: ContractRevisionTarget) : RevisionTargetResult

    /** MASTER 404 `MERCHANT_NOT_FOUND`. */
    data object MerchantNotFound : RevisionTargetResult

    /** 가맹점은 있으나 그 계약·Revision이 상세에 없다. */
    data object RevisionNotFound : RevisionTargetResult

    data class Unavailable(val reason: String) : RevisionTargetResult
}

/**
 * MASTER 응답 분류(§9.5 매핑 표 — C-28). 확정 응답만 종결한다.
 * - [Decided]: 200 저장된 판정(재생 포함 — [replayed]) 또는 결정 조회 DECIDED([fingerprint]·[decidedVia]·[operation] 있음). SUCCEEDED면 [reference], REJECTED면 [rejection].
 * - [None]: 결정 조회 NONE(아직 커밋된 결정 없음 — 효과 없음 아님).
 * - [NotYetDue]: 기한 확인 — MASTER 시각상 기한 전(저장 없음).
 * - [KeyReused]: 409 `IDEMPOTENCY_KEY_REUSED` — 같은 키에 다른 지문의 결정(무결성 플래그, 실행 POST 중단).
 * - [Malformed]: 400 `REQUEST_MALFORMED`·413 — 저장 안 됨, 같은 바이트는 계속 거부(재전송 중단 `OWNER_MALFORMED`).
 * - [Unknown]: 401·404([alert] — `MASTER_UNAUTHORIZED`·`MASTER_NOT_FOUND`), 503 code, timeout·연결 끊김·해석 불가·permit 없음 — 효과 모름.
 */
sealed interface MerchantApprovalResult {
    data class Decided(
        val succeeded: Boolean,
        val reference: String?,
        val rejection: String?,
        val replayed: Boolean,
        val operation: String? = null,
        val fingerprint: String? = null,
        val decidedVia: String? = null,
    ) : MerchantApprovalResult {
        init {
            require(succeeded == (reference != null && rejection == null)) { "a success has a reference and no rejection" }
            require(!succeeded == (rejection != null && reference == null)) { "a rejection has a code and no reference" }
        }
    }

    data object None : MerchantApprovalResult

    data object NotYetDue : MerchantApprovalResult

    data object KeyReused : MerchantApprovalResult

    data class Malformed(val code: String) : MerchantApprovalResult

    data class Unknown(val failure: String, val alert: Boolean) : MerchantApprovalResult
}

/**
 * 가맹점 승인 실행 시간 정책(C-19 — **Demo 값, 운영값 아님**, LIVE Gate; 기본값 없음). [executionWindow] ≤ MASTER `max-execution-window`(Demo PT24H).
 * [staleAfter] = connect + read timeout + 30초(승인 TX1이 실행 행 다음 시도로 둔다 — 웹 TX2와 회복 job이 겹치지 않게). [deadlineGrace] ≥ MASTER skew + read timeout.
 * [backoff]: 회복 다음 시도 간격(시작 [backoffInitial]에서 두 배씩, 상한 [backoffMax] — Demo PT30M).
 */
interface MerchantApprovalPolicy {
    val executionWindow: Duration
    val staleAfter: Duration
    val deadlineGrace: Duration
    val backoffInitial: Duration
    val backoffMax: Duration

    fun backoff(attempt: Int): Duration {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val next = backoffInitial.multipliedBy(1L shl shift)
        return if (next > backoffMax) backoffMax else next
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}

/**
 * 첫 MID 발급 대상 사실(MASTER 상세 한 번의 읽기). [revision]·[site]는 상세에 없으면 null, [agreementExists]는 그 계약의 CSA 목록 안인지. [siteInScope]는 Revision Scope가 MERCHANT이거나
 * Site가 Scope Site 목록 안인지. 사업자등록번호·채널 식별값 없음.
 */
data class FirstMidTarget(
    val merchantId: String,
    val merchantName: String,
    val accountCount: Int,
    val contractId: String,
    val agreementId: String,
    val agreementExists: Boolean,
    val revision: ContractRevisionTarget?,
    val primarySiteId: String,
    val siteLifecycle: String?,
    val siteChannelType: String?,
    val siteInScope: Boolean,
    /** MERCHANT-CODE-001 MC-3a(§5.1·§5.3): MASTER 상세 `merchant.merchantCode`(요청 시점 현재 코드 — 승인 행 표기·pinned 표시값). */
    val merchantCode: String,
)

sealed interface FirstMidTargetResult {
    data class Found(val target: FirstMidTarget) : FirstMidTargetResult

    data object MerchantNotFound : FirstMidTargetResult

    data class Unavailable(val reason: String) : FirstMidTargetResult
}

/** 승인 TX1 안에서 만드는 MASTER 실행 본문(순수 계산 — 코드 공유 없이 MASTER와 같은 계약). */
interface MerchantApprovalContract {
    @Suppress("LongParameterList")
    fun contractRevisionApproval(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, contractId: String, revisionNo: Int,
    ): MerchantApprovalCommandOutput

    /** B2-3: 명령 필드 merchantId, contractId, agreementId, revisionNo, primarySiteId(§9.4 순서). */
    @Suppress("LongParameterList")
    fun firstDefaultIssuance(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantId: String, contractId: String,
        agreementId: String, revisionNo: Int, primarySiteId: String,
    ): MerchantApprovalCommandOutput

    /** B3-1: `operation` = ApprovePaymentMethodActivation, 명령 필드 activationId, primarySiteId(C-45). */
    @Suppress("LongParameterList")
    fun paymentMethodActivationApproval(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, activationId: String, primarySiteId: String,
    ): MerchantApprovalCommandOutput

    /** B3-1: `operation` = DecideSiteReview, 명령 필드 merchantSiteId, reasonCode(C-37 — REVIEW_PASSED). */
    @Suppress("LongParameterList")
    fun siteReviewApproval(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantSiteId: String, reasonCode: String,
    ): MerchantApprovalCommandOutput

    /**
     * B3-2(§9.26): `operation` = EnableCurrency, 명령 필드 merchantAccountId, currency, effectiveFrom, effectiveTo. [effectiveFromMode] `AT_EXECUTION`이면 본문 `effectiveFrom` = 문자열
     * `AT_EXECUTION` 그대로(승인 시각을 넣지 않는다), `DATE`면 [effectiveFromDate](KST 날짜)의 00:00 KST UTC 시각. [effectiveToDate]도 같은 변환(C-7 배타 끝), 없으면 키 없음.
     */
    @Suppress("LongParameterList")
    fun currencyEnablement(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantAccountId: String, currency: String,
        effectiveFromMode: String, effectiveFromDate: LocalDate?, effectiveToDate: LocalDate?,
    ): MerchantApprovalCommandOutput

    /** MERCHANT-CODE-001 MC-1b(§3.7.2): `operation` = ChangeMerchantCode, 명령 필드 merchantId, currentCode, newCode, reasonCode(등록자 필드 없음 — Q-MC-10). */
    @Suppress("LongParameterList")
    fun merchantCodeChange(
        approvalId: String, requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, evidenceRef: String, merchantId: String, currentCode: String,
        newCode: String, reasonCode: String,
    ): MerchantApprovalCommandOutput
}

/** [fingerprint] = `mop1:` 지문(ADMIN 실행 행 지문 = MASTER 근거 지문), [body] = 실행·기한 확인 POST 바이트. */
class MerchantApprovalCommandOutput(val fingerprint: String, body: ByteArray) {
    private val bytes = body.copyOf()

    fun body(): ByteArray = bytes.copyOf()
}

/** 가맹점 상세의 승인 대기 표시(C-24 — ADMIN DB jOOQ 조회, 구현은 approval 모듈). 승인 목록 권한이 없는 조회자면 빈 목록. */
fun interface MerchantPendingApprovals {
    fun pendingFor(viewerActorId: String, merchantId: String): List<PendingMerchantApproval>
}

/** [revisionNo] = 계약 승인의 대상 Revision(첫 MID 발급은 null — 종류가 대상을 말한다). Demo 개선 C-2. */
data class PendingMerchantApproval(val approvalId: String, val kind: String, val status: String, val revisionNo: Int? = null)
