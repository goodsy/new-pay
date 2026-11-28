package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.BusinessCriticalChangeQuery
import com.paycore.master.merchant.application.provided.BusinessCriticalChangeUseCase
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessFieldError
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfileRuleViolation
import com.paycore.master.merchant.domain.MasterRejection
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사업자 명령 채널(BUSINESS-PROFILE-001 §6.1·§6.6·§6.7 — BIZ-2, ADMIN 서명 채널 뒤). 대상 ID·version은 **서명된 본문**에 있다(기존 C-2). 명령 실행은 provided
 * [BusinessProfileCommandUseCase](MASTER 로컬 TX 하나 — BIZ-1), 중복 요약은 provided [BusinessProfileQueryUseCase.existingSummary](TX 밖 — r-2). 이 컨트롤러는 TX를 열지 않는다.
 *
 * 응답(기존 MerchantOperationsController 틀): 성공 200 `{"outcome":"SUCCEEDED","reference","replayed"}`, 저장된 거절 200 `{"outcome":"REJECTED","rejection","replayed"}` —
 * **거절 reference는 버린다(C-12)**. 예외는 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE` 하나: 저장 reference로 기존 사업자 요약을 읽어 `existing`(상호·원문 번호(D-BIZ-007·D-PII-001 ① — 마스킹 아님)·내부 키·등록 상태 —
 * D-BIZ-003·C-14)을 더한다. 요약을 읽지 못하면(예외·없음) **503 `TEMPORARILY_UNAVAILABLE`**(existing 없는 REJECTED를 내보내지 않는다 — m-1·C-33). 거절은 이미 저장됐으므로 같은 키
 * 재전송은 저장 거절 재생 + 요약 재조회다.
 *
 * 400: 구조 위반 `REQUEST_MALFORMED`, 값 규칙 위반 `BUSINESS_VALIDATION_FAILED` + `fieldErrors`(path·code만 — 저장 안 함, C-16). 잠근 행 상태로만 알 수 있는 규칙 위반(REGISTERED
 * 사업자 수정에서 필수값 삭제)은 Domain 재검사의 **[BusinessProfileRuleViolation]만** 같은 400으로 바꾼다(rev 0.2.3 m-1 — TX는 이미 rollback, `admin_request` 0행).
 * `IllegalArgumentException` 전반은 잡지 않는다(다른 결함은 503 그대로). 판정 순서는 Domain이 정한다 — 저장된 거절(버전·변경 없음·사유 필요)이 규칙 위반보다 먼저(BIZ-2-11).
 * 그 밖: 키 재사용 409, 진행 중·커밋 불명·기술 실패 503(같은 키로 다시), 쓰기 permit 없음 503 `ADMIN_WRITE_BUSY`(Use Case 미호출).
 *
 * 로그·지표: 연산·결과 분류·상관 ID·오류 개수만(입력 값·번호·이름·예외 문구 없음). 지표 `paycore.master.admin_channel.command{operation,outcome}`(기존 계열 — 값 label 없음).
 *
 * BIZ-CRIT-APPROVAL-001 §5.1 `critical-change-proposals`(제안 — 1인 기록 명령): 같은 틀. 성공 응답에 `proposal` 요약(proposalId·baseVersion·contentDigest·changedFields·
 * representativeChanged(중요 대표자 변경만)·subjectCorrection·requestedBy — **상호·개인정보 원문 없음**, M-5)을 붙인다. 요약은 commit 뒤 조회 Use Case로 읽고(재생도 같은 경로),
 * 읽지 못하면 503(제안은 이미 저장 — 같은 키 재전송이 재생 + 재조회).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/businesses/commands")
class BusinessCommandController(
    private val commands: BusinessProfileCommandUseCase,
    private val query: BusinessProfileQueryUseCase,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
    private val criticalChanges: BusinessCriticalChangeUseCase,
    private val criticalChangeQuery: BusinessCriticalChangeQuery,
) {
    private val log = LoggerFactory.getLogger(BusinessCommandController::class.java)

    @PostMapping("/create")
    fun create(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "CreateBusiness", BusinessProfileRequestParser::create, { it.request }, commands::create)

    @PostMapping("/update")
    fun update(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "UpdateBusiness", BusinessProfileRequestParser::update, { it.request }, commands::update)

    @PostMapping("/complete-registration")
    fun completeRegistration(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "CompleteBusinessRegistration", BusinessProfileRequestParser::completeRegistration, { it.request }, commands::completeRegistration)

    @PostMapping("/critical-change-proposals")
    fun proposeCriticalChange(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "ProposeBusinessCriticalChange", BusinessProfileRequestParser::criticalChangeProposal, { it.request }, criticalChanges::propose) { command, reference ->
            criticalChangeQuery.proposal(command.businessId, BusinessChangeProposalId(reference))?.let { p ->
                linkedMapOf(
                    "proposalId" to p.proposalId, "baseVersion" to p.baseVersion, "contentDigest" to p.contentDigest, "changedFields" to p.changedFields,
                    "representativeChanged" to p.representativeChanged, "subjectCorrection" to p.subjectCorrection, "requestedBy" to p.requestedBy,
                )
            }
        }

    /** permit(대기 0) → 엄격 해석 → Use Case → 응답. 해석 실패는 Use Case를 부르지 않는다(저장 없음). [summary]가 있으면 성공 응답에 `proposal` 요약을 붙인다(null = 읽기 실패 → 503). */
    private fun <C> command(
        request: HttpServletRequest,
        operation: String,
        parse: (ByteArray) -> BusinessCommandParse<C>,
        adminRequest: (C) -> AdminRequest,
        execute: (C) -> AdminCommandResult,
        summary: ((C, String) -> Map<String, Any?>?)? = null,
    ): ResponseEntity<ByteArray> =
        permits.write(operation, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_WRITE_BUSY) }) {
            val command = when (val parsed = parse(request.inputStream.readAllBytes())) {
                BusinessCommandParse.Malformed -> {
                    log.info("operation={} requestId={} result=REQUEST_MALFORMED", operation, AdminChannelResponses.requestId(request))
                    return@write AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
                }
                is BusinessCommandParse.Invalid -> return@write invalid(request, operation, null, parsed.errors)
                is BusinessCommandParse.Parsed -> parsed.command
            }
            val correlationId = adminRequest(command).correlationId
            try {
                val result = execute(command)
                if (summary != null && result is AdminCommandResult.Succeeded) {
                    val proposal = summary(command, result.reference) ?: run {
                        count(operation, FAILED)
                        log.warn(
                            "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE stage=PROPOSAL_SUMMARY failure=MISSING",
                            operation, AdminChannelResponses.requestId(request), correlationId,
                        )
                        return@write AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
                    }
                    count(operation, SUCCEEDED)
                    log.info("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, SUCCEEDED)
                    return@write AdminChannelResponses.json(
                        request, OK, LinkedHashMap(BusinessProfileJson.succeeded(result.reference, result.replayed)).apply { put("proposal", proposal) },
                    )
                }
                respond(request, operation, correlationId, result)
            } catch (e: BusinessProfileRuleViolation) {
                // rev 0.2.3 m-1(C-16 보충): Domain 재검사 위반만 400 — TX 전체 rollback이라 효과 없음 확정(ADMIN은 OUTCOME_UNKNOWN으로 다루지 않는다).
                invalid(request, operation, correlationId, e.errors)
            } catch (e: AdminCommandFailure) {
                failed(request, operation, correlationId, e)
            } catch (e: RuntimeException) {
                // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값 — 등록번호·개인정보 — 이 섞일 수 있다). 분류(타입 이름)만.
                count(operation, FAILED)
                log.warn(
                    "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                    operation, AdminChannelResponses.requestId(request), correlationId, e.javaClass.simpleName,
                )
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private fun invalid(request: HttpServletRequest, operation: String, correlationId: String?, errors: List<BusinessFieldError>): ResponseEntity<ByteArray> {
        log.info(
            "operation={} requestId={} correlationId={} result=BUSINESS_VALIDATION_FAILED errors={}",
            operation, AdminChannelResponses.requestId(request), correlationId, errors.size,
        )
        return AdminChannelResponses.json(request, BAD_REQUEST, BusinessProfileJson.validationFailed(errors))
    }

    private fun respond(request: HttpServletRequest, operation: String, correlationId: String, result: AdminCommandResult): ResponseEntity<ByteArray> {
        val (outcome, body) = when (result) {
            is AdminCommandResult.Succeeded -> SUCCEEDED to BusinessProfileJson.succeeded(result.reference, result.replayed)
            is AdminCommandResult.Rejected -> if (result.rejection == MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE) {
                val existing = existing(request, operation, correlationId, result.reference)
                    ?: return AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
                REJECTED to BusinessProfileJson.rejected(result.rejection.name, result.replayed, existing)
            } else {
                // C-12: 다른 거절의 reference는 여기서 버린다(키 자체를 만들지 않는다).
                REJECTED to BusinessProfileJson.rejected(result.rejection.name, result.replayed)
            }
            AdminCommandResult.IdempotencyKeyReused -> {
                count(operation, KEY_REUSED)
                log.info("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, KEY_REUSED)
                return AdminChannelResponses.error(request, CONFLICT, AdminChannelResponses.IDEMPOTENCY_KEY_REUSED)
            }
        }
        count(operation, outcome)
        log.info("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, outcome)
        return AdminChannelResponses.json(request, OK, body)
    }

    /** D-BIZ-003 중복 요약(m-1): 읽지 못하면 null → 호출자가 503(거절은 이미 저장 — 같은 키 재전송이 재생 + 재조회). */
    private fun existing(
        request: HttpServletRequest,
        operation: String,
        correlationId: String,
        reference: String?,
    ): com.paycore.master.merchant.application.provided.BusinessSummary? {
        val summary = try {
            reference?.let { query.existingSummary(BusinessId(it)) }
        } catch (e: RuntimeException) {
            log.warn(
                "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE stage=EXISTING_SUMMARY failure={}",
                operation, AdminChannelResponses.requestId(request), correlationId, e.javaClass.simpleName,
            )
            count(operation, FAILED)
            return null
        }
        if (summary == null) {
            // 저장 reference의 사업자가 없다(삭제 경로 없음 — 결함). 값 없이 분류만.
            log.warn(
                "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE stage=EXISTING_SUMMARY failure=MISSING",
                operation, AdminChannelResponses.requestId(request), correlationId,
            )
            count(operation, FAILED)
        }
        return summary
    }

    private fun failed(request: HttpServletRequest, operation: String, correlationId: String, e: AdminCommandFailure): ResponseEntity<ByteArray> {
        val code = when (e) {
            is AdminCommandFailure.RequestInProgress -> AdminChannelResponses.REQUEST_IN_PROGRESS
            is AdminCommandFailure.OutcomeUnknown -> AdminChannelResponses.OUTCOME_UNKNOWN
            is AdminCommandFailure.IdentifierCollision, is AdminCommandFailure.TemporarilyUnavailable -> AdminChannelResponses.TEMPORARILY_UNAVAILABLE
        }
        count(operation, FAILED)
        log.warn("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, code)
        return AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, code)
    }

    private fun count(operation: String, outcome: String) {
        registry.counter(MerchantOperationsController.COMMAND_METRIC, "operation", operation, "outcome", outcome).increment()
    }

    private companion object {
        const val SUCCEEDED = "SUCCEEDED"
        const val REJECTED = "REJECTED"
        const val KEY_REUSED = "KEY_REUSED"
        const val FAILED = "FAILED"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
        const val SERVICE_UNAVAILABLE = 503
    }
}
