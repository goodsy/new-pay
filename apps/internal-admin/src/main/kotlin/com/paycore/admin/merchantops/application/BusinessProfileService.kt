package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.merchantops.application.provided.BusinessProfileContent
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import com.paycore.admin.merchantops.application.provided.BusinessProfileResult
import com.paycore.admin.merchantops.application.provided.BusinessProfileUseCase
import com.paycore.admin.merchantops.application.required.BusinessOpsMetrics
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

/**
 * [BusinessProfileUseCase](BUSINESS-PROFILE-001 §7.2 — `MerchantRegistrationService` 순서 그대로):
 * 1. 앞단(ACTIVE + 업무 조회 역할 — 아니면 403, 감사 없음 — BIZ-3-1) → 권한 상수(create [BusinessPermissions.CREATE]·update [BusinessPermissions.UPDATE]·등록 완료
 *    [BusinessPermissions.COMPLETE] — 아니면 403 + 감사 `FORBIDDEN`).
 * 2. 형식([BusinessProfileInputRules] — 위반 400 + fieldErrors, MASTER 미호출). create는 번호가 오가는 시도라 감사 `INVALID_INPUT`(D-MOPS-006 ④), update·등록 완료는 지표만.
 * 3. 등록 창(`registrations-per-minute` — 이 서비스 자체 창) → **create만 등록번호 버킷 1회 원자 예약**(공유 [RegistrationNumberBudget] — C-13). 초과 429 + 감사 `RATE_LIMITED`.
 * 4. 시도 감사 `REQUESTED`(ADMIN 로컬 TX) — 실패하면 503 `AUDIT_UNAVAILABLE`, MASTER 미호출, 예약 환불.
 * 5. MASTER(DB TX 밖, 키 `mo1.<계정 ULID>.<submissionId>` — 계정 이름공간 C-4, correlationId `mos.<submissionId>` — MASTER 이력·Outbox와 대조 C-14).
 * 6. 버킷: 중복 거절·결과 불명·RetrySameKey(중복 요약 조회 실패 503 포함 — m-1)·Busy는 **확정**(결과가 중복일 수 있다), 성공·다른 거절·키 재사용·MASTER 400(효과 없음 확정)은 **환불**.
 * 7. 결과 감사 → 응답. 실패하면 503 `AUDIT_UNAVAILABLE`(MASTER 효과는 이미 있을 수 있다 — 같은 submissionId 재전송이 재생 결과로 감사를 다시 시도).
 *
 * MASTER 400 `BUSINESS_VALIDATION_FAILED`(복제 규칙과 어긋남)는 **효과 없음 확정**(C-16 보충 — OUTCOME_UNKNOWN 아님): 400 + 같은 fieldErrors + 결과 감사 `INVALID_INPUT` + 지표
 * `business_rule_drift`. 중복 거절의 `existing`은 행위자가 [BusinessPermissions.VIEW]를 가질 때만 응답에 싣는다(D-BIZ-003 — create는 OPERATOR라 항상 보유하지만 판정을 코드로 남긴다),
 * 감사 targetRef = 기존 businessId(C-14). 감사 detail: create `sub=<8>;replayed;rejection;status=DRAFT|REGISTERED;brn=123*******`, update·등록 완료 `sub=<8>;replayed;rejection;version=<n>;
 * reason=PRESENT|ABSENT`(사유 원문은 MASTER 이력에만 — C-13). 로그: 연산·결과 분류·상관 ID만.
 */
@Service
class BusinessProfileService(
    transactionManager: PlatformTransactionManager,
    sessions: AdminSessionQuery,
    recorder: AdminAuditRecorder,
    private val gateway: MasterBusinessGateway,
    limits: MerchantOpsLimits,
    private val metrics: MerchantOpsMetrics,
    private val businessMetrics: BusinessOpsMetrics,
    private val registrationNumbers: RegistrationNumberBudget,
) : BusinessProfileUseCase {

    private val audit = BusinessAudit(transactionManager, sessions, recorder)
    private val registrations = ActorMinuteWindows({ limits.registrationsPerMinute })

    override fun execute(actorId: String, request: BusinessProfileRequest, context: AdminRequestContext): BusinessProfileResult {
        val operation = request.operation
        val view = audit.frontGate(actorId) ?: return finish(operation, "FORBIDDEN", BusinessProfileResult.Forbidden, context.correlationId)
        val pre = PreDetail(request)
        fun reject(resultCode: String, outcome: BusinessProfileResult) = finish(
            operation, resultCode,
            if (audit.record(view, context, context.correlationId, operation.auditAction, resultCode, pre.text(), pre.target)) outcome else BusinessProfileResult.AuditUnavailable,
            context.correlationId,
        )

        if (view.roles.none { it in permission(operation) }) return reject("FORBIDDEN", BusinessProfileResult.Forbidden)
        if (!BusinessProfileInputRules.identifiersWellFormed(request)) return invalid(operation, BusinessProfileResult.InvalidInput(), ::reject, context)
        val fieldErrors = BusinessProfileInputRules.errors(request)
        if (fieldErrors.isNotEmpty()) return invalid(operation, BusinessProfileResult.InvalidInput(fieldErrors), ::reject, context)
        if (registrations.tryReserve(actorId) == null) {
            metrics.rateLimited(REGISTRATION)
            return reject("RATE_LIMITED", BusinessProfileResult.RateLimited)
        }
        val reservation = if (operation == BusinessProfileOperation.CREATE) {
            registrationNumbers.reserve(actorId) ?: run {
                metrics.rateLimited(REGISTRATION_NUMBER)
                return reject("RATE_LIMITED", BusinessProfileResult.RateLimited)
            }
        } else {
            null
        }

        val correlationId = CORRELATION_PREFIX + request.submissionId
        if (!audit.record(view, context, correlationId, operation.auditAction, "REQUESTED", pre.text(), pre.target)) {
            reservation?.let(registrationNumbers::refund)
            return finish(operation, "AUDIT_UNAVAILABLE", BusinessProfileResult.AuditUnavailable, correlationId)
        }

        val result = checkedReference(request, gateway.command(command(actorId, request, correlationId)))

        if (reservation != null && !confirmsRegistrationNumber(result)) registrationNumbers.refund(reservation)
        val (resultCode, outcome) = classify(view, result)
        val decided = result as? MasterBusinessCommandResult.Decided
        val detail = pre.text(replayed = decided?.replayed, rejection = decided?.rejection)
        // D-BIZ-003·C-14: 성공 = 만든(또는 대상) ID, 중복 거절 = 기존 businessId, 그 밖 = 입력 대상(update·등록 완료의 경로 ID — create는 없음).
        val targetRef = decided?.reference ?: (decided?.existing?.get("businessId") as String?) ?: pre.target
        if (!audit.record(view, context, correlationId, operation.auditAction, resultCode, detail, targetRef)) {
            return finish(operation, "AUDIT_UNAVAILABLE", BusinessProfileResult.AuditUnavailable, correlationId)
        }
        when (result) {
            is MasterBusinessCommandResult.Invalid -> {
                businessMetrics.ruleDrift(operation.masterOperation)
                log.warn("operation=business-profile kind={} correlationId={} result=BUSINESS_VALIDATION_FAILED errors={}", operation, correlationId, result.fieldErrors.size)
            }
            is MasterBusinessCommandResult.RetrySameKey, is MasterBusinessCommandResult.Unknown -> {
                metrics.commandUnknown(operation.masterOperation)
                log.warn("operation=business-profile kind={} requestId={} correlationId={} result={} reason={}", operation, context.correlationId, correlationId, resultCode, reason(result))
            }
            else -> Unit
        }
        return finish(operation, resultCode, outcome, correlationId)
    }

    override fun rejectMalformed(actorId: String, operation: BusinessProfileOperation, businessId: String?, context: AdminRequestContext): BusinessProfileResult {
        val view = audit.frontGate(actorId) ?: return finish(operation, "FORBIDDEN", BusinessProfileResult.Forbidden, context.correlationId)
        val forbidden = view.roles.none { it in permission(operation) }
        val target = businessId?.takeIf { BusinessAudit.BUSINESS_ID.matches(it) }
        // create(번호가 오가는 시도)는 감사 — 본문에서 아무것도 꺼내지 않는다(detail 고정 brn=INVALID). update·등록 완료의 형식 위반은 지표만, 403은 감사.
        val (resultCode, outcome) = if (forbidden) "FORBIDDEN" to BusinessProfileResult.Forbidden else "INVALID_INPUT" to BusinessProfileResult.InvalidInput()
        if (!forbidden && operation != BusinessProfileOperation.CREATE) return finish(operation, resultCode, outcome, context.correlationId)
        val detail = if (operation == BusinessProfileOperation.CREATE) BusinessAudit.INVALID_NUMBER else null
        val audited = audit.record(view, context, context.correlationId, operation.auditAction, resultCode, detail, target)
        return finish(operation, resultCode, if (audited) outcome else BusinessProfileResult.AuditUnavailable, context.correlationId)
    }

    /** 형식 위반: create는 감사(D-MOPS-006 ④), update·등록 완료는 지표만(감사 없음). */
    private fun invalid(
        operation: BusinessProfileOperation,
        outcome: BusinessProfileResult.InvalidInput,
        reject: (String, BusinessProfileResult) -> BusinessProfileResult,
        context: AdminRequestContext,
    ): BusinessProfileResult =
        if (operation == BusinessProfileOperation.CREATE) reject("INVALID_INPUT", outcome) else finish(operation, "INVALID_INPUT", outcome, context.correlationId)

    private fun permission(operation: BusinessProfileOperation): Set<String> = when (operation) {
        BusinessProfileOperation.CREATE -> BusinessPermissions.CREATE
        BusinessProfileOperation.UPDATE -> BusinessPermissions.UPDATE
        BusinessProfileOperation.COMPLETE_REGISTRATION -> BusinessPermissions.COMPLETE
    }

    private fun command(actorId: String, request: BusinessProfileRequest, correlationId: String): MasterBusinessCommand {
        check(ACCOUNT_ID.matches(actorId)) { "session actor id format" }
        // C-4: 계정별 이름공간 — 다른 운영자가 같은 submissionId를 보내도 MASTER 키가 다르다. 63자.
        val key = "mo1.${actorId.removePrefix(ACCOUNT_PREFIX)}.${request.submissionId}"
        val body = linkedMapOf<String, Any?>()
        when (request) {
            is BusinessProfileRequest.Create -> {
                body["registrationStatus"] = request.registrationStatus
                body["registrationNumber"] = request.registrationNumber
            }
            is BusinessProfileRequest.Change -> {
                body["businessId"] = request.businessId
                body["version"] = request.version
                body["reason"] = request.reason
            }
        }
        content(body, request.content)
        return MasterBusinessCommand(request.operation, key, "$ACTOR_PREFIX$actorId", correlationId, body)
    }

    /** MASTER §6.6 본문 — 모든 key, 선택 값은 JSON null(명령 본문 규칙. 목록 검색과 다르다). 목록은 요청 순서 그대로(지문 순서). */
    private fun content(body: MutableMap<String, Any?>, c: BusinessProfileContent) {
        body["businessType"] = c.businessType
        body["displayName"] = c.displayName
        body["registration"] = c.registration.let {
            linkedMapOf(
                "corporateRegistrationNumber" to it.corporateRegistrationNumber, "openingDate" to it.openingDate, "countryCode" to it.countryCode,
                "englishName" to it.englishName, "phoneNumber" to it.phoneNumber, "email" to it.email,
            )
        }
        body["representatives"] = c.representatives.map { linkedMapOf("name" to it.name, "representativeType" to it.representativeType) }
        body["categories"] = c.categories.map { linkedMapOf("categoryType" to it.categoryType, "name" to it.name) }
        body["addresses"] = c.addresses.map {
            linkedMapOf("addressType" to it.addressType, "postalCode" to it.postalCode, "baseAddress" to it.baseAddress, "detailAddress" to it.detailAddress)
        }
        body["contacts"] = c.contacts.map { linkedMapOf("name" to it.name, "email" to it.email, "phoneNumber" to it.phoneNumber, "primary" to it.primary) }
    }

    /**
     * 수정·등록 완료 성공 reference는 **보낸 사업자**여야 한다(MASTER는 대상 businessId를 돌려준다). 다르면 이 요청의 결과라고 볼 수 없으므로 결과 불명(`REFERENCE_MISMATCH`)으로 둔다 —
     * 효과 유무를 단정하지 않고 같은 submissionId 재전송으로 확인한다(fail-closed, `MerchantRegistrationService.checkedReference`와 같은 규칙).
     */
    private fun checkedReference(request: BusinessProfileRequest, result: MasterBusinessCommandResult): MasterBusinessCommandResult {
        if (request !is BusinessProfileRequest.Change || result !is MasterBusinessCommandResult.Decided || !result.succeeded) return result
        return if (result.reference == request.businessId) result else MasterBusinessCommandResult.Unknown(REFERENCE_MISMATCH)
    }

    /** C-13·m-1: 중복 거절은 "그 번호가 있다"는 조회 결과와 같은 정보 → 확정. 결과 불명·503(중복 요약 조회 실패 포함)·Busy도 확정(중복이었을 수 있다). */
    private fun confirmsRegistrationNumber(result: MasterBusinessCommandResult): Boolean = when (result) {
        is MasterBusinessCommandResult.Decided -> result.rejection == DUPLICATE_NUMBER
        MasterBusinessCommandResult.KeyReused, is MasterBusinessCommandResult.Invalid -> false
        is MasterBusinessCommandResult.Busy, is MasterBusinessCommandResult.RetrySameKey, is MasterBusinessCommandResult.Unknown -> true
    }

    private fun classify(view: AdminSessionView, result: MasterBusinessCommandResult): Pair<String, BusinessProfileResult> = when (result) {
        is MasterBusinessCommandResult.Decided -> {
            // D-BIZ-003: 기존 사업자 요약은 사업자 조회 권한자에게만(판정을 코드로 남긴다 — create 권한 OPERATOR는 VIEW에 포함).
            val existing = result.existing?.takeIf { view.roles.any { it in BusinessPermissions.VIEW } }
            (if (result.succeeded) "OK" else "REJECTED") to
                BusinessProfileResult.Decided(if (result.succeeded) SUCCEEDED else REJECTED, result.reference, result.rejection, result.replayed, existing)
        }
        is MasterBusinessCommandResult.Invalid -> "INVALID_INPUT" to BusinessProfileResult.InvalidInput(result.fieldErrors)
        MasterBusinessCommandResult.KeyReused -> "KEY_REUSED" to BusinessProfileResult.KeyReused
        is MasterBusinessCommandResult.Busy -> "BUSY" to BusinessProfileResult.MasterBusy
        is MasterBusinessCommandResult.RetrySameKey -> OUTCOME_UNKNOWN to BusinessProfileResult.RetrySameKey
        is MasterBusinessCommandResult.Unknown -> OUTCOME_UNKNOWN to BusinessProfileResult.OutcomeUnknown
    }

    private fun reason(result: MasterBusinessCommandResult): String = when (result) {
        is MasterBusinessCommandResult.RetrySameKey -> result.reason
        is MasterBusinessCommandResult.Unknown -> result.reason
        else -> "-"
    }

    private fun finish(operation: BusinessProfileOperation, resultCode: String, outcome: BusinessProfileResult, correlationId: String): BusinessProfileResult {
        metrics.command(operation.masterOperation, if (outcome == BusinessProfileResult.AuditUnavailable) "AUDIT_UNAVAILABLE" else resultCode)
        log.info("operation=business-profile kind={} correlationId={} result={}", operation, correlationId, outcome.javaClass.simpleName)
        return outcome
    }

    /** 입력에서 감사에 남겨도 되는 값만(형식이 맞을 때만 — 자유 문구·평문 없음, C-13). */
    private class PreDetail(request: BusinessProfileRequest) {
        private val sub = request.submissionId.takeIf { BusinessProfileInputRules.SUBMISSION_ID.matches(it) }?.take(SUB_PREFIX)
        private val tail: List<String> = when (request) {
            is BusinessProfileRequest.Create -> listOfNotNull(
                request.registrationStatus.takeIf { it in BusinessProfileInputRules.CREATE_STATUSES }?.let { "status=$it" },
                BusinessAudit.maskedNumber(request.registrationNumber),
            )
            // 사유는 있음·없음만(reason=PRESENT|ABSENT — 원문은 MASTER master_change_history, C-13).
            is BusinessProfileRequest.Change -> listOf("version=${request.version}", "reason=" + if (request.reason == null) "ABSENT" else "PRESENT")
        }
        val target: String? = (request as? BusinessProfileRequest.Change)?.businessId?.takeIf { BusinessAudit.BUSINESS_ID.matches(it) }

        fun text(replayed: Boolean? = null, rejection: String? = null): String? =
            (listOfNotNull(sub?.let { "sub=$it" }, replayed?.let { "replayed=$it" }, rejection?.let { "rejection=$it" }) + tail).joinToString(";").ifEmpty { null }
    }

    private companion object {
        val log = LoggerFactory.getLogger(BusinessProfileService::class.java)
        const val ACCOUNT_PREFIX = "adm_"
        val ACCOUNT_ID = Regex("^adm_[0-9A-HJKMNP-TV-Z]{26}$")
        const val ACTOR_PREFIX = "admin:"
        const val CORRELATION_PREFIX = "mos."
        const val DUPLICATE_NUMBER = "BUSINESS_REGISTRATION_NUMBER_DUPLICATE"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
        const val REFERENCE_MISMATCH = "REFERENCE_MISMATCH"
        const val SUCCEEDED = "SUCCEEDED"
        const val REJECTED = "REJECTED"
        const val REGISTRATION = "REGISTRATION"
        const val REGISTRATION_NUMBER = "REGISTRATION_NUMBER"
        const val SUB_PREFIX = 8
    }
}
