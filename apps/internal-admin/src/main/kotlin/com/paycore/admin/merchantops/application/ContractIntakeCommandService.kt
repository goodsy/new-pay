package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AdminAuditTrail
import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessProfileResult
import com.paycore.admin.merchantops.application.provided.ContractIntakeCommandUseCase
import com.paycore.admin.merchantops.application.provided.ContractIntakeOperation
import com.paycore.admin.merchantops.application.provided.ContractIntakeRequest
import com.paycore.admin.merchantops.application.required.BusinessOpsMetrics
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterContractIntakeCommand
import com.paycore.admin.merchantops.application.required.MasterContractIntakeGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.LocalDate

/**
 * [ContractIntakeCommandUseCase](MERCHANT-FLOW-001 §4B.7·§4B.11 표 2b 행 — 접수 건 생성·전이·계약 완료). 순서는 사업자 명령([BusinessProfileService])과 같다:
 * 1. 앞단(ACTIVE + 업무 조회 역할 — 아니면 403, 감사 없음) → [BusinessPermissions.CONTRACT_INTAKE](OPERATOR — 아니면 403 + 감사 `FORBIDDEN`).
 * 2. ADMIN 1차 검사([ContractIntakeInputRules] 형식 + 시각 의존 검사, 계약 담당자 = ACTIVE OPERATOR 계정 — §4A.6 n-3) → 400 + fieldErrors(감사 없음·지표만 — 사업자 수정 관례, MASTER 미호출).
 *    **같은 submissionId 재전송**(앞선 시도 감사 `REQUESTED`가 있음 — [AdminAuditTrail])은 시각·계정 의존 검사를 다시 하지 않는다(n-3: 저장된 결과를 MASTER에서 회수 — 그 사이 담당자가 비활성이
 *    되었거나 KST 자정이 지나도 처음 판정을 유지). 형식 검사는 같은 본문이면 결과가 같아 늘 한다. 시도 조회 실패는 503 `AUDIT_UNAVAILABLE`(fail-closed).
 * 3. 명령 창(`registrations-per-minute` — 이 서비스 자체 창, Demo 값) 초과 429 + 감사 `RATE_LIMITED`.
 * 4. 시도 감사 `REQUESTED`(ADMIN 로컬 TX) — 실패하면 503, MASTER 미호출.
 * 5. MASTER(DB TX 밖, 키 `mo1.<계정 ULID>.<submissionId>` — 사업자 명령과 같은 계정 이름공간, 상관 ID `mos.<submissionId>`, 담당자 `admin:adm_…`). 성공 reference가 보낸 사업자가 아니면 결과 불명.
 * 6. 결과 감사 → 응답(실패하면 503 — 같은 submissionId 재전송이 재생 결과로 감사를 다시 시도).
 *
 * 감사 detail(§4B.11): `sub=<8>;replayed;rejection;kind|target=…;reason=PRESENT|ABSENT;evidence=PRESENT|ABSENT` — 사유·근거 원문·담당자 키는 없다(원문은 MASTER 전이·변경 이력, 근거는 상세 열람).
 * FLOW-2d 접수 항목 수정은 `target=ITEMS`(바뀐 항목 이름·값은 MASTER 변경 이력에만). 담당자는 화면 불투명 참조를 웹 adapter가 푼 계정 키(D-GOV-024 — 풀리지 않으면 400 NOT_ALLOWED).
 * 로그: 연산·결과 분류·상관 ID만. 금융 효과 없음(사업자 계약은 새 가맹점 등록 자격 — 결제 권리·Snapshot·Outbox와 무관, §4B.12).
 */
@Service
class ContractIntakeCommandService internal constructor(
    transactionManager: PlatformTransactionManager,
    private val sessions: AdminSessionQuery,
    recorder: AdminAuditRecorder,
    private val trail: AdminAuditTrail,
    private val gateway: MasterContractIntakeGateway,
    limits: MerchantOpsLimits,
    private val metrics: MerchantOpsMetrics,
    private val businessMetrics: BusinessOpsMetrics,
    private val clock: () -> Instant,
) : ContractIntakeCommandUseCase {

    @Autowired
    @Suppress("LongParameterList")
    constructor(
        transactionManager: PlatformTransactionManager,
        sessions: AdminSessionQuery,
        recorder: AdminAuditRecorder,
        trail: AdminAuditTrail,
        gateway: MasterContractIntakeGateway,
        limits: MerchantOpsLimits,
        metrics: MerchantOpsMetrics,
        businessMetrics: BusinessOpsMetrics,
    ) : this(transactionManager, sessions, recorder, trail, gateway, limits, metrics, businessMetrics, Instant::now)

    private val audit = BusinessAudit(transactionManager, sessions, recorder)
    private val window = ActorMinuteWindows({ limits.registrationsPerMinute })

    override fun execute(actorId: String, request: ContractIntakeRequest, context: AdminRequestContext): BusinessProfileResult {
        val operation = request.operation
        val view = audit.frontGate(actorId) ?: return finish(operation, "FORBIDDEN", BusinessProfileResult.Forbidden, context.correlationId)
        val pre = PreDetail(request)
        fun reject(resultCode: String, outcome: BusinessProfileResult) = finish(
            operation, resultCode,
            if (audit.record(view, context, context.correlationId, operation.auditAction, resultCode, pre.text(), pre.target)) outcome else BusinessProfileResult.AuditUnavailable,
            context.correlationId,
        )

        if (view.roles.none { it in BusinessPermissions.CONTRACT_INTAKE }) return reject("FORBIDDEN", BusinessProfileResult.Forbidden)
        if (!ContractIntakeInputRules.identifiersWellFormed(request)) return finish(operation, INVALID_INPUT, BusinessProfileResult.InvalidInput(), context.correlationId)
        val correlationId = CORRELATION_PREFIX + request.submissionId
        val checks = ContractIntakeInputRules.check(request, LocalDate.ofInstant(clock(), BusinessContractProgress.SEOUL))
        if (checks.format.isNotEmpty()) return invalid(operation, checks.format + checks.timely + managerErrors(request), context)
        // n-3(Coordinator 결정 — 가역): 계약 담당자 ACTIVE OPERATOR 검사는 재전송에도 **항상** 한다(계정 상태가 바뀌었으면 400 — 재전송이 정지 계정을 담당자로 통과시키지 않게).
        // 재전송에서 건너뛰는 것은 시각 의존 검사(접수일 미래·시작일 과거)뿐이다 — 자정을 넘긴 재전송이 저장된 결과를 회수하지 못하는 일을 막는다.
        val manager = managerErrors(request)
        val dependent = checks.timely
        if (dependent.isNotEmpty()) {
            // n-3: 이미 시도한 요청(같은 submissionId — REQUESTED 감사 있음)은 시각 의존 검사를 다시 하지 않는다(MASTER가 저장된 결과를 돌려준다).
            val resent = try {
                trail.requested(view.actorId, operation.auditAction, correlationId)
            } catch (e: RuntimeException) {
                log.warn("operation=contract-intake kind={} correlationId={} result=trail-unavailable failure={}", operation, correlationId, e.javaClass.simpleName)
                return finish(operation, AUDIT_UNAVAILABLE, BusinessProfileResult.AuditUnavailable, correlationId)
            }
            if (!resent) return invalid(operation, dependent + manager, context)
        }
        if (manager.isNotEmpty()) return invalid(operation, manager, context)
        if (window.tryReserve(actorId) == null) {
            metrics.rateLimited(RATE_KIND)
            return reject("RATE_LIMITED", BusinessProfileResult.RateLimited)
        }
        if (!audit.record(view, context, correlationId, operation.auditAction, "REQUESTED", pre.text(), pre.target)) {
            return finish(operation, AUDIT_UNAVAILABLE, BusinessProfileResult.AuditUnavailable, correlationId)
        }

        val result = checkedReference(request, gateway.contractIntakeCommand(command(actorId, request, correlationId)))

        val (resultCode, outcome) = classify(result)
        val decided = result as? MasterBusinessCommandResult.Decided
        if (!audit.record(view, context, correlationId, operation.auditAction, resultCode, pre.text(replayed = decided?.replayed, rejection = decided?.rejection), pre.target)) {
            return finish(operation, AUDIT_UNAVAILABLE, BusinessProfileResult.AuditUnavailable, correlationId)
        }
        when (result) {
            is MasterBusinessCommandResult.Invalid -> {
                businessMetrics.ruleDrift(operation.masterOperation)
                log.warn("operation=contract-intake kind={} correlationId={} result=BUSINESS_VALIDATION_FAILED errors={}", operation, correlationId, result.fieldErrors.size)
            }
            is MasterBusinessCommandResult.RetrySameKey, is MasterBusinessCommandResult.Unknown -> {
                metrics.commandUnknown(operation.masterOperation)
                log.warn("operation=contract-intake kind={} requestId={} correlationId={} result={} reason={}", operation, context.correlationId, correlationId, resultCode, reason(result))
            }
            else -> Unit
        }
        return finish(operation, resultCode, outcome, correlationId)
    }

    override fun rejectMalformed(actorId: String, operation: ContractIntakeOperation, businessId: String?, context: AdminRequestContext): BusinessProfileResult {
        val view = audit.frontGate(actorId) ?: return finish(operation, "FORBIDDEN", BusinessProfileResult.Forbidden, context.correlationId)
        if (view.roles.any { it in BusinessPermissions.CONTRACT_INTAKE }) return finish(operation, INVALID_INPUT, BusinessProfileResult.InvalidInput(), context.correlationId)
        val target = businessId?.takeIf { BusinessAudit.BUSINESS_ID.matches(it) }
        val audited = audit.record(view, context, context.correlationId, operation.auditAction, "FORBIDDEN", null, target)
        return finish(operation, "FORBIDDEN", if (audited) BusinessProfileResult.Forbidden else BusinessProfileResult.AuditUnavailable, context.correlationId)
    }

    /**
     * §4A.6 n-3: 계약 담당자는 ACTIVE + OPERATOR 역할 계정(ADMIN 자기 `admin_account` — 계정 현재 행). FLOW-2d(D-GOV-024): 화면 참조가 이 세션 대응표로 풀리지 않았으면(null)
     * 같은 `contractManager NOT_ALLOWED`(fail-closed). 형식 위반 키는 [ContractIntakeInputRules]가 FORMAT으로 본다(웹 경로에서는 생기지 않는 방어).
     */
    private fun managerErrors(request: ContractIntakeRequest): List<BusinessFieldError> {
        val managerId = when (request) {
            is ContractIntakeRequest.Open -> request.contractManagerId
            is ContractIntakeRequest.UpdateItems -> request.contractManagerId
            else -> return emptyList()
        } ?: return listOf(BusinessFieldError(CONTRACT_MANAGER, NOT_ALLOWED))
        if (!ContractIntakeInputRules.ADMIN_ACCOUNT_ID.matches(managerId)) return emptyList()
        val manager: AdminSessionView? = sessions.current(managerId)
        return if (manager != null && manager.isActive && OPERATOR in manager.roles) emptyList() else listOf(BusinessFieldError(CONTRACT_MANAGER, NOT_ALLOWED))
    }

    private fun invalid(operation: ContractIntakeOperation, errors: List<BusinessFieldError>, context: AdminRequestContext): BusinessProfileResult =
        finish(operation, INVALID_INPUT, BusinessProfileResult.InvalidInput(ContractIntakeInputRules.sorted(errors)), context.correlationId)

    private fun command(actorId: String, request: ContractIntakeRequest, correlationId: String): MasterContractIntakeCommand {
        check(ContractIntakeInputRules.ADMIN_ACCOUNT_ID.matches(actorId)) { "session actor id format" }
        // C-4: 계정별 이름공간 — 다른 운영자가 같은 submissionId를 보내도 MASTER 키가 다르다(사업자 명령과 같은 형식, 63자).
        val key = "mo1.${actorId.removePrefix(ACCOUNT_PREFIX)}.${request.submissionId}"
        val body = linkedMapOf<String, Any?>("businessId" to request.businessId)
        when (request) {
            is ContractIntakeRequest.Open -> {
                body["expectedKind"] = request.expectedKind
                body["receivedOn"] = request.receivedOn
                body["intakeChannel"] = request.intakeChannel
                body["contractManager"] = ACTOR_PREFIX + checkNotNull(request.contractManagerId) { "manager checked before the call" }
                body["desiredServices"] = request.desiredServices
            }
            is ContractIntakeRequest.Move -> {
                body["intakeSeq"] = request.intakeSeq
                body["expectedVersion"] = request.expectedVersion
                body["target"] = request.target
            }
            is ContractIntakeRequest.Complete -> {
                body["intakeSeq"] = request.intakeSeq
                body["expectedVersion"] = request.expectedVersion
                body["documentRef"] = request.documentRef
                body["startDate"] = request.startDate
                body["endDate"] = request.endDate
                body["services"] = request.services
            }
            is ContractIntakeRequest.UpdateItems -> {
                body["intakeSeq"] = request.intakeSeq
                body["expectedVersion"] = request.expectedVersion
                body["receivedOn"] = request.receivedOn
                body["intakeChannel"] = request.intakeChannel
                body["contractManager"] = ACTOR_PREFIX + checkNotNull(request.contractManagerId) { "manager checked before the call" }
                body["desiredServices"] = request.desiredServices
            }
            is ContractIntakeRequest.ContractEvent -> {
                body["contractSeq"] = request.contractSeq
                body["expectedEventCount"] = request.expectedEventCount
            }
        }
        body["reason"] = request.reason
        if (request is ContractIntakeRequest.ContractEvent) body["evidenceRef"] = request.evidenceRef
        return MasterContractIntakeCommand(request.operation, key, ACTOR_PREFIX + actorId, correlationId, body)
    }

    /** 성공 reference는 보낸 사업자여야 한다. 다르면 이 요청의 결과라고 볼 수 없으므로 결과 불명(`REFERENCE_MISMATCH` — 같은 submissionId 재전송으로 확인, fail-closed). */
    private fun checkedReference(request: ContractIntakeRequest, result: MasterBusinessCommandResult): MasterBusinessCommandResult {
        if (result !is MasterBusinessCommandResult.Decided || !result.succeeded) return result
        return if (result.reference == request.businessId) result else MasterBusinessCommandResult.Unknown(REFERENCE_MISMATCH)
    }

    private fun classify(result: MasterBusinessCommandResult): Pair<String, BusinessProfileResult> = when (result) {
        is MasterBusinessCommandResult.Decided ->
            (if (result.succeeded) "OK" else "REJECTED") to BusinessProfileResult.Decided(if (result.succeeded) SUCCEEDED else REJECTED, result.reference, result.rejection, result.replayed)
        is MasterBusinessCommandResult.Invalid -> INVALID_INPUT to BusinessProfileResult.InvalidInput(result.fieldErrors)
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

    private fun finish(operation: ContractIntakeOperation, resultCode: String, outcome: BusinessProfileResult, correlationId: String): BusinessProfileResult {
        metrics.command(operation.masterOperation, if (outcome == BusinessProfileResult.AuditUnavailable) AUDIT_UNAVAILABLE else resultCode)
        log.info("operation=contract-intake kind={} correlationId={} result={}", operation, correlationId, outcome.javaClass.simpleName)
        return outcome
    }

    /** 감사에 남겨도 되는 값만(형식이 맞을 때만 — 원문 없음): 종류(생성)·목표 상태(전이·완료), 사유·근거의 있음/없음. */
    private class PreDetail(request: ContractIntakeRequest) {
        private val sub = request.submissionId.takeIf { ContractIntakeInputRules.SUBMISSION_ID.matches(it) }?.take(SUB_PREFIX)
        private val tail: List<String> = listOfNotNull(
            when (request) {
                is ContractIntakeRequest.Open -> request.expectedKind.takeIf { it in ContractIntakeInputRules.KINDS }?.let { "kind=$it" }
                is ContractIntakeRequest.Move -> request.target.takeIf { it in ContractIntakeInputRules.MOVE_TARGETS }?.let { "target=$it" }
                is ContractIntakeRequest.Complete -> "target=$COMPLETED"
                is ContractIntakeRequest.ContractEvent -> "kind=${request.operation.name}"
                is ContractIntakeRequest.UpdateItems -> "target=ITEMS"
            },
            "reason=" + if (request.reason == null) ABSENT else PRESENT,
            "evidence=" + if (request is ContractIntakeRequest.Complete || (request is ContractIntakeRequest.ContractEvent && request.evidenceRef != null)) PRESENT else ABSENT,
        )
        val target: String? = request.businessId.takeIf { BusinessAudit.BUSINESS_ID.matches(it) }

        fun text(replayed: Boolean? = null, rejection: String? = null): String =
            (listOfNotNull(sub?.let { "sub=$it" }, replayed?.let { "replayed=$it" }, rejection?.let { "rejection=$it" }) + tail).joinToString(";")
    }

    private companion object {
        val log = LoggerFactory.getLogger(ContractIntakeCommandService::class.java)
        const val ACCOUNT_PREFIX = "adm_"
        const val ACTOR_PREFIX = "admin:"
        const val CORRELATION_PREFIX = "mos."
        const val OPERATOR = "OPERATOR"
        const val CONTRACT_MANAGER = "contractManager"
        const val NOT_ALLOWED = "NOT_ALLOWED"
        const val INVALID_INPUT = "INVALID_INPUT"
        const val AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE"
        const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
        const val REFERENCE_MISMATCH = "REFERENCE_MISMATCH"
        const val SUCCEEDED = "SUCCEEDED"
        const val REJECTED = "REJECTED"
        const val COMPLETED = "COMPLETED"
        const val PRESENT = "PRESENT"
        const val ABSENT = "ABSENT"
        const val RATE_KIND = "CONTRACT_INTAKE"
        const val SUB_PREFIX = 8
    }
}
