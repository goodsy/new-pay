package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.provided.MerchantRegistrationResult
import com.paycore.admin.merchantops.application.provided.MerchantRegistrationUseCase
import com.paycore.admin.merchantops.application.provided.RegistrationKind
import com.paycore.admin.merchantops.application.provided.RegistrationRequest
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandKind
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [MerchantRegistrationUseCase](MERCHANT-OPS-002 §4.4·§6). 순서·감사:
 * 1. 역할: 계정 현재 행 ACTIVE + OPERATOR(D-MOPS-006 ①). 아니면 403 + 감사 `FORBIDDEN`(5종 모두 — B1-2 계약 초안·CSA 포함).
 * 2. 형식([RegistrationInputRules] — C-10). 위반 400, MASTER 미호출. 사업자 등록만 감사 `INVALID_INPUT`(등록번호가 오간 모든 시도 — D-MOPS-006 ④), 다른 종류는 지표만.
 * 3. 등록 버킷(actor별 분당) 초과 → 429 + 감사 `RATE_LIMITED`. 사업자 등록은 **등록번호 버킷 1회를 원자적으로 예약**(C-13 — 남은 횟수 0이면 429 + 감사).
 * 4. 시도 감사 행 `REQUESTED`를 ADMIN 로컬 TX로 커밋 — 실패하면 503 `AUDIT_UNAVAILABLE`, MASTER 미호출, 예약 환불.
 * 5. MASTER 호출(DB TX 밖 — gateway가 TX 안 호출을 거부).
 * 6. 등록번호 예약: 중복 거절·결과 불명(Unknown·RetrySameKey)·Busy(503)는 **확정**(중복이었을 수 있다 — 보수적), 성공·다른 거절·키 재사용(409)은 **환불**(같은 창에서만, 0 아래 없음).
 * 7. 결과 감사 행 → 응답. 실패하면 503 `AUDIT_UNAVAILABLE`(MASTER 효과는 이미 커밋됐을 수 있다 — 같은 submissionId 재전송이 재생 결과로 감사를 다시 시도한다).
 *
 * 감사 행 수: MASTER까지 간 시도 = 2행(REQUESTED → 결과), 호출 전 거절(403·400·429·시도 감사 실패 전) = 결과 1행. correlationId: MASTER로 가는 시도는 `mos.<submissionId>`(C-14 — 재전송마다 같아
 * MASTER 변경 이력·Outbox와 대조된다, MASTER 키가 아니다), 호출 전 거절은 ADMIN 요청 ID(n-4). detail은 고정 `key=value`(`sub=<submissionId 앞 8자>;replayed;rejection` + 사업자 `brn=앞3*******`
 * 또는 `brn=INVALID`, Site `channel=WEB|APP`, 가맹점 `code=<가맹점 코드>`(MERCHANT-CODE-001 MC-2-2 — 공개값, 형식이 맞을 때만)) — 등록번호 평문·기존 businessId·이름·채널 식별값·MASTER 키 원문 없음. targetRef: 성공 = 만든 ID(`ctr_`·`csa_` 포함), 그 밖 = 입력의 상위 ID
 * (가맹점 = 사업자, Site = 가맹점, 계약 = 대상 가맹점, CSA = 계약, Revision = 계약 — 성공도 계약 ID + detail `rev=<n>` C-22), 사업자는 성공만. 계약 날짜는 KST 날짜 → 그 날 00:00 KST의 UTC 시각(C-7)으로 바꿔 보내고 소급 판정은 MASTER에 맡긴다(§3.4).
 * 로그: 연산·결과 분류·상관 ID만(MASTER 키 원문 없음). 결과 불명은 WARN + `command_unknown` 지표.
 */
@Service
class MerchantRegistrationService(
    transactionManager: PlatformTransactionManager,
    private val sessions: AdminSessionQuery,
    private val gateway: MasterOpsGateway,
    private val recorder: AdminAuditRecorder,
    limits: MerchantOpsLimits,
    private val metrics: MerchantOpsMetrics,
    private val registrationNumbers: RegistrationNumberBudget,
) : MerchantRegistrationUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val registrations = ActorMinuteWindows({ limits.registrationsPerMinute })

    override fun register(actorId: String, request: RegistrationRequest, context: AdminRequestContext): MerchantRegistrationResult {
        val kind = request.kind
        val view = sessions.current(actorId) ?: return finish(kind, "FORBIDDEN", MerchantRegistrationResult.Forbidden, context.correlationId)
        val pre = PreDetail(request)
        fun reject(resultCode: String, outcome: MerchantRegistrationResult) =
            finish(kind, resultCode, if (audited(view, context, context.correlationId, kind, resultCode, pre.text(), pre.parentRef)) outcome else MerchantRegistrationResult.AuditUnavailable, context.correlationId)

        if (!view.isActive || OPERATOR !in view.roles) return reject("FORBIDDEN", MerchantRegistrationResult.Forbidden)
        if (!RegistrationInputRules.wellFormed(request)) {
            if (kind != RegistrationKind.BUSINESS) return finish(kind, "INVALID_INPUT", MerchantRegistrationResult.InvalidInput, context.correlationId)
            return reject("INVALID_INPUT", MerchantRegistrationResult.InvalidInput)
        }
        if (registrations.tryReserve(actorId) == null) {
            metrics.rateLimited(REGISTRATION)
            return reject("RATE_LIMITED", MerchantRegistrationResult.RateLimited)
        }
        val reservation = if (kind == RegistrationKind.BUSINESS) {
            registrationNumbers.reserve(actorId) ?: run {
                metrics.rateLimited(REGISTRATION_NUMBER)
                return reject("RATE_LIMITED", MerchantRegistrationResult.RateLimited)
            }
        } else {
            null
        }

        val correlationId = CORRELATION_PREFIX + request.submissionId
        if (!audited(view, context, correlationId, kind, "REQUESTED", pre.text(), pre.parentRef)) {
            reservation?.let(registrationNumbers::refund)
            return finish(kind, "AUDIT_UNAVAILABLE", MerchantRegistrationResult.AuditUnavailable, correlationId)
        }

        val result = gateway.command(command(actorId, request, correlationId)).let { checkedReference(request, it) }

        if (reservation != null && !confirmsRegistrationNumber(result)) registrationNumbers.refund(reservation)
        val (resultCode, outcome) = classify(result)
        val decided = result as? MasterCommandResult.Decided
        // C-22(B2-1): Revision 성공 reference `ctr_…#<n>`은 감사 targetRef 형식 밖 → targetRef = 계약 ID, detail에 `rev=<n>`.
        val revisionNo = if (kind == RegistrationKind.CONTRACT_REVISION) decided?.reference?.substringAfter('#', "")?.ifEmpty { null } else null
        val detail = pre.text(replayed = decided?.replayed, rejection = decided?.rejection, revision = revisionNo)
        // B3-1(§9.16): 개통 등록 targetRef = MID, Site 심사 거절 targetRef = siteId(성공 reference `site_…#n`은 targetRef 형식 밖) — 성공·거절 모두 입력의 대상.
        val targetRef = if (kind in TARGET_IS_INPUT) pre.parentRef else decided?.reference ?: pre.parentRef
        if (!audited(view, context, correlationId, kind, resultCode, detail, targetRef)) {
            return finish(kind, "AUDIT_UNAVAILABLE", MerchantRegistrationResult.AuditUnavailable, correlationId)
        }
        if (resultCode == OUTCOME_UNKNOWN) {
            metrics.commandUnknown(kind.name)
            log.warn("operation=merchant-registration kind={} requestId={} correlationId={} result=OUTCOME_UNKNOWN reason={}", kind, context.correlationId, correlationId, reason(result))
        }
        return finish(kind, resultCode, outcome, correlationId)
    }

    override fun rejectMalformed(actorId: String, kind: RegistrationKind, registrationNumberAttempt: Boolean, context: AdminRequestContext): MerchantRegistrationResult {
        if (kind != RegistrationKind.BUSINESS || !registrationNumberAttempt) return finish(kind, "INVALID_INPUT", MerchantRegistrationResult.InvalidInput, context.correlationId)
        val view = sessions.current(actorId) ?: return finish(kind, "FORBIDDEN", MerchantRegistrationResult.Forbidden, context.correlationId)
        // 본문에서 아무것도 꺼내지 않는다 — detail은 고정 brn=INVALID(M-1: 해석 실패면 403이어도 INVALID).
        val (resultCode, outcome) = if (!view.isActive || OPERATOR !in view.roles) "FORBIDDEN" to MerchantRegistrationResult.Forbidden else "INVALID_INPUT" to MerchantRegistrationResult.InvalidInput
        val audited = audited(view, context, context.correlationId, kind, resultCode, INVALID_NUMBER, null)
        return finish(kind, resultCode, if (audited) outcome else MerchantRegistrationResult.AuditUnavailable, context.correlationId)
    }

    private fun command(actorId: String, request: RegistrationRequest, correlationId: String): MasterCommand {
        check(ACCOUNT_ID.matches(actorId)) { "session actor id format" }
        // C-4: 계정별 이름공간 — 다른 운영자가 같은 submissionId를 보내도 MASTER 키가 다르다(남의 결과를 재생받지 않는다). 63자.
        val key = "mo1.${actorId.removePrefix(ACCOUNT_PREFIX)}.${request.submissionId}"
        val (kind, fields) = when (request) {
            is RegistrationRequest.Business -> MasterCommandKind.REGISTER_BUSINESS to mapOf("registrationNumber" to request.registrationNumber, "displayName" to request.displayName)
            // MERCHANT-CODE-001 MC-1: 코드를 그대로 전달(형식은 위에서 검사 — 예약어·중복 판정은 MASTER).
            is RegistrationRequest.Merchant -> MasterCommandKind.REGISTER_MERCHANT to
                mapOf("businessId" to request.businessId, "displayName" to request.displayName, "merchantCode" to request.merchantCode)
            is RegistrationRequest.MerchantSite -> MasterCommandKind.REGISTER_MERCHANT_SITE to
                mapOf("merchantId" to request.merchantId, "channelType" to request.channelType, "channelIdentifier" to request.channelIdentifier)
            // C-7: KST 날짜 → 그 날 00:00 KST의 UTC 시각(종료일은 배타). 시작 소급 판정은 MASTER Domain(§3.4) — 여기서는 바꾸거나 막지 않는다.
            is RegistrationRequest.Contract -> MasterCommandKind.REGISTER_CONTRACT to buildMap<String, Any> {
                put("partyBusinessId", request.partyBusinessId)
                put("scopeMerchantId", request.scopeMerchantId)
                put("scopeSiteIds", request.scopeSiteIds.toList())
                put("effectiveFrom", RegistrationInputRules.kstStartOfDay(request.effectiveFromDate))
                request.effectiveToDate?.let { put("effectiveTo", RegistrationInputRules.kstStartOfDay(it)) }
            }
            is RegistrationRequest.ServiceAgreement -> MasterCommandKind.ADD_SERVICE_AGREEMENT to
                mapOf("contractId" to request.contractId, "serviceKind" to request.serviceKind)
            // B2-1(§9.2): 계약 초안과 같은 C-7 날짜 변환. 시작 소급 판정은 MASTER Domain(D-MOPS-007 ②).
            is RegistrationRequest.ContractRevision -> MasterCommandKind.REGISTER_CONTRACT_REVISION to buildMap<String, Any> {
                put("contractId", request.contractId)
                put("scopeMerchantId", request.scopeMerchantId)
                put("scopeSiteIds", request.scopeSiteIds.toList())
                put("effectiveFrom", RegistrationInputRules.kstStartOfDay(request.effectiveFromDate))
                request.effectiveToDate?.let { put("effectiveTo", RegistrationInputRules.kstStartOfDay(it)) }
            }
            // B3-1(§9.14): 개통 등록·Site 심사 거절(REJECTED 고정 — 결정값 필드 없음 C-40). 근거 참조는 있을 때만 키를 보낸다(선택).
            is RegistrationRequest.PaymentMethodActivation -> MasterCommandKind.REGISTER_PAYMENT_METHOD_ACTIVATION to
                mapOf("merchantAccountId" to request.merchantAccountId, "paymentMethod" to request.paymentMethod)
            is RegistrationRequest.SiteReviewRejection -> MasterCommandKind.REJECT_SITE_REVIEW to buildMap<String, Any> {
                put("merchantSiteId", request.merchantSiteId)
                put("reasonCode", request.reasonCode)
                request.evidenceRef?.let { put("evidenceRef", it) }
            }
        }
        return MasterCommand(kind, key, "$ACTOR_PREFIX$actorId", correlationId, fields)
    }

    /**
     * 리뷰 n-3(B2-1): Revision 성공 reference는 **보낸 계약의** Revision(`<contractId>#<n>`)이어야 한다. 다른 계약·번호 없는 값이면 응답이 이 요청의 결과라고 볼 수 없으므로
     * 결과 불명([MasterCommandResult.Unknown] `REFERENCE_MISMATCH`)으로 둔다 — 효과 유무를 단정하지 않고 같은 submissionId 재전송으로 확인한다(fail-closed, 응답 모양 검사 다음 단계).
     */
    private fun checkedReference(request: RegistrationRequest, result: MasterCommandResult): MasterCommandResult {
        if (result !is MasterCommandResult.Decided || result.outcome != MasterCommandResult.Outcome.SUCCEEDED) return result
        // B3-1: Site 심사 거절 성공 reference도 **보낸 Site의** 결정(`<siteId>#<seq>`)이어야 한다(같은 규칙).
        val own = when (request) {
            is RegistrationRequest.ContractRevision -> Regex("^${Regex.escape(request.contractId)}#[1-9][0-9]*$")
            is RegistrationRequest.SiteReviewRejection -> Regex("^${Regex.escape(request.merchantSiteId)}#[1-9][0-9]*$")
            else -> return result
        }
        return if (result.reference?.let(own::matches) == true) result else MasterCommandResult.Unknown(REFERENCE_MISMATCH)
    }

    /** C-13: 중복 거절은 "그 번호가 있다"는 검색 결과와 같은 정보 → 확정. 결과 불명·503도 확정(중복이었을 수 있다). */
    private fun confirmsRegistrationNumber(result: MasterCommandResult): Boolean = when (result) {
        is MasterCommandResult.Decided -> result.rejection == DUPLICATE_NUMBER
        MasterCommandResult.KeyReused -> false
        is MasterCommandResult.Busy, is MasterCommandResult.RetrySameKey, is MasterCommandResult.Unknown -> true
    }

    private fun classify(result: MasterCommandResult): Pair<String, MerchantRegistrationResult> = when (result) {
        is MasterCommandResult.Decided -> (if (result.outcome == MasterCommandResult.Outcome.SUCCEEDED) "OK" else "REJECTED") to
            MerchantRegistrationResult.Decided(result.outcome.name, result.reference, result.rejection, result.replayed)
        MasterCommandResult.KeyReused -> "KEY_REUSED" to MerchantRegistrationResult.KeyReused
        is MasterCommandResult.Busy -> "BUSY" to MerchantRegistrationResult.MasterBusy
        is MasterCommandResult.RetrySameKey -> OUTCOME_UNKNOWN to MerchantRegistrationResult.RetrySameKey
        is MasterCommandResult.Unknown -> OUTCOME_UNKNOWN to MerchantRegistrationResult.OutcomeUnknown
    }

    private fun reason(result: MasterCommandResult): String = when (result) {
        is MasterCommandResult.RetrySameKey -> result.reason
        is MasterCommandResult.Unknown -> result.reason
        else -> "-"
    }

    private fun finish(kind: RegistrationKind, resultCode: String, outcome: MerchantRegistrationResult, correlationId: String): MerchantRegistrationResult {
        metrics.command(kind.name, if (outcome == MerchantRegistrationResult.AuditUnavailable) "AUDIT_UNAVAILABLE" else resultCode)
        log.info("operation=merchant-registration kind={} correlationId={} result={}", kind, correlationId, outcome.javaClass.simpleName)
        return outcome
    }

    /** 감사 1행(ADMIN 로컬 TX). 실패하면 false(감사 실패 = 행위 실패). */
    @Suppress("LongParameterList")
    private fun audited(
        view: AdminSessionView,
        context: AdminRequestContext,
        correlationId: String,
        kind: RegistrationKind,
        resultCode: String,
        detail: String?,
        targetRef: String?,
    ): Boolean = try {
        transactions.execute({ false }) {
            recorder.record(
                AuditRecord(
                    occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = view.actorId, actorDisplayName = view.displayName,
                    action = kind.auditAction, resultCode = resultCode, correlationId = correlationId, remoteIp = context.remoteIp, detail = detail, targetRef = targetRef,
                ),
            )
        }
        true
    } catch (e: RuntimeException) {
        log.warn("operation=merchant-registration-audit action={} result=audit-unavailable failure={}", kind.auditAction, e.javaClass.simpleName)
        false
    }

    /** 입력에서 감사에 남겨도 되는 값만 고른다(형식이 맞을 때만 — 자유 문구·평문 없음). */
    private class PreDetail(request: RegistrationRequest) {
        private val sub = request.submissionId.takeIf { RegistrationInputRules.SUBMISSION_ID.matches(it) }?.take(SUB_PREFIX)
        private val brn = (request as? RegistrationRequest.Business)?.registrationNumber?.let {
            if (RegistrationInputRules.REGISTRATION_NUMBER.matches(it)) "brn=" + it.take(VISIBLE_DIGITS) + "*".repeat(MASKED_DIGITS) else INVALID_NUMBER
        }
        private val channel = (request as? RegistrationRequest.MerchantSite)?.channelType?.takeIf { it in CHANNEL_TYPES }?.let { "channel=$it" }

        /** B3-1 Site 심사 거절 사유 코드(고정 목록 값만 — 근거 참조 값은 넣지 않는다). */
        private val reason = (request as? RegistrationRequest.SiteReviewRejection)?.reasonCode?.takeIf { it in RegistrationInputRules.REJECT_REASONS }?.let { "reason=$it" }

        /** MERCHANT-CODE-001 MC-2-2: 가맹점 등록 코드(공개값 — 비밀·인증 근거 아님, L-5). §3.1 형식일 때만(형식 위반 입력은 감사에 옮기지 않는다). */
        private val code = (request as? RegistrationRequest.Merchant)?.merchantCode?.takeIf { RegistrationInputRules.MERCHANT_CODE.matches(it) }?.let { "code=$it" }
        val parentRef: String? = when (request) {
            is RegistrationRequest.Business -> null
            is RegistrationRequest.Merchant -> request.businessId.takeIf { RegistrationInputRules.BUSINESS_ID.matches(it) }
            is RegistrationRequest.MerchantSite -> request.merchantId.takeIf { RegistrationInputRules.MERCHANT_ID.matches(it) }
            // 계약의 상위 = 운영자가 입력한 대상 가맹점(조회 화면이 가맹점 중심), CSA의 상위 = 입력한 계약.
            is RegistrationRequest.Contract -> request.scopeMerchantId.takeIf { RegistrationInputRules.MERCHANT_ID.matches(it) }
            is RegistrationRequest.ServiceAgreement -> request.contractId.takeIf { RegistrationInputRules.CONTRACT_ID.matches(it) }
            // B2-1: Revision의 상위 = 입력한 계약(성공도 계약 ID — C-22).
            is RegistrationRequest.ContractRevision -> request.contractId.takeIf { RegistrationInputRules.CONTRACT_ID.matches(it) }
            // B3-1: 개통 등록의 대상 = MID, Site 심사 거절의 대상 = Site.
            is RegistrationRequest.PaymentMethodActivation -> request.merchantAccountId.takeIf { RegistrationInputRules.MERCHANT_ACCOUNT_ID.matches(it) }
            is RegistrationRequest.SiteReviewRejection -> request.merchantSiteId.takeIf { RegistrationInputRules.MERCHANT_SITE_ID.matches(it) }
        }

        fun text(replayed: Boolean? = null, rejection: String? = null, revision: String? = null): String? = listOfNotNull(
            sub?.let { "sub=$it" }, replayed?.let { "replayed=$it" }, rejection?.let { "rejection=$it" }, revision?.let { "rev=$it" }, brn, channel, reason, code,
        ).joinToString(";").ifEmpty { null }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MerchantRegistrationService::class.java)
        const val OPERATOR = "OPERATOR"
        const val ACCOUNT_PREFIX = "adm_"
        val ACCOUNT_ID = Regex("^adm_[0-9A-HJKMNP-TV-Z]{26}$")
        const val ACTOR_PREFIX = "admin:"
        const val CORRELATION_PREFIX = "mos."
        const val DUPLICATE_NUMBER = "BUSINESS_REGISTRATION_NUMBER_DUPLICATE"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
        const val REFERENCE_MISMATCH = "REFERENCE_MISMATCH"
        const val INVALID_NUMBER = "brn=INVALID"
        const val REGISTRATION = "REGISTRATION"
        const val REGISTRATION_NUMBER = "REGISTRATION_NUMBER"
        const val SUB_PREFIX = 8
        const val VISIBLE_DIGITS = 3
        const val MASKED_DIGITS = 7
        val CHANNEL_TYPES = setOf("WEB", "APP")
        val TARGET_IS_INPUT = setOf(RegistrationKind.CONTRACT_REVISION, RegistrationKind.PAYMENT_METHOD_ACTIVATION, RegistrationKind.SITE_REVIEW_REJECTION)
    }
}
