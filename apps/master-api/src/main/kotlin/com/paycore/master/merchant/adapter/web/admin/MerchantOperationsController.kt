package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ADMIN 가맹점 등록 명령(MERCHANT-OPS-002 §3 — B1-1: 사업자·가맹점·Site, B1-2: 계약 초안·CSA). ADMIN 서명 채널(서명 v1·본문 16 KiB·query 금지·`ADMIN_SERVICE` — D-MOPS-005: 조회 키 하나로 서명) 뒤에서만
 * 열리고, ID는 경로가 아니라 **서명된 본문**에 있다(C-2). 명령 실행은 [MerchantOnboardingUseCase](MASTER 로컬 TX 하나 — 멱등 기록·업무 변경·변경 이력·Outbox·권리 Snapshot 같은 TX).
 * 이 컨트롤러는 TX를 열지 않고 저장 Port를 직접 부르지 않는다.
 *
 * 응답(§3.3): 저장된 판정은 200 — 성공 `{"outcome":"SUCCEEDED","reference":…,"replayed":…}`, 업무 거절 `{"outcome":"REJECTED","rejection":…,"replayed":…}`.
 * **거절에는 `reference` 키를 내보내지 않는다(C-12, D-MOPS-006 ③)**: Use Case는 거절 근거 ID(예: 같은 등록번호의 기존 businessId, 다른 가맹점의 siteId)를 `admin_request`에
 * 그대로 저장·재생하지만, 그 ID가 관리 채널을 건너지 않게 이 adapter가 모든 거절에서 버린다. 키 재사용 409 `IDEMPOTENCY_KEY_REUSED`, 같은 키 진행 중 503 `REQUEST_IN_PROGRESS`,
 * 커밋 불명 503 `OUTCOME_UNKNOWN`, 그 밖 기술 실패 503 `TEMPORARILY_UNAVAILABLE`(모두 같은 키로 다시), 쓰기 permit 없음 503 `ADMIN_WRITE_BUSY`(Use Case 미호출), 본문 위반 400
 * `REQUEST_MALFORMED`(저장 없음). 오류 본문에 요청 값이 없다.
 *
 * **이 쓰기 채널에 nonce가 필요 없는 이유**(review m-3): 서명 범위인 본문에 `idempotencyKey`가 들어 있다. 시계 창(Demo 300초) 안에서 같은 바이트를 다시 보내면 같은 키·같은 본문이므로
 * `admin_request`가 재생 결과를 돌려줄 뿐 새 행·새 효과가 없다(키를 바꾸면 서명이 깨진다). 창 밖은 401. 따라서 재전송 공격은 기존 결과의 재조회로 수렴한다.
 *
 * 로그·지표: 연산·결과 분류·상관 ID만(`mos.<submissionId>` — MASTER 멱등 키 원문·등록번호·채널 식별값·예외 문구 없음). 지표 `paycore.master.admin_channel.command{operation,outcome}`
 * (outcome = SUCCEEDED·REJECTED·KEY_REUSED·FAILED). 서블릿 웹 컨텍스트에서만(비웹 demo-seed 명령에는 채널 설정이 없다).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/merchant-operations")
class MerchantOperationsController(
    private val onboarding: MerchantOnboardingUseCase,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(MerchantOperationsController::class.java)

    @PostMapping("/businesses")
    fun registerBusiness(request: HttpServletRequest): ResponseEntity<ByteArray> = command(request, "RegisterBusiness") {
        MerchantOperationsRequestParser.business(it)?.let { c -> c.request to { onboarding.registerBusiness(c) } }
    }

    @PostMapping("/merchants")
    fun registerMerchant(request: HttpServletRequest): ResponseEntity<ByteArray> = command(request, "RegisterMerchant") {
        MerchantOperationsRequestParser.merchant(it)?.let { c -> c.request to { onboarding.registerMerchant(c) } }
    }

    @PostMapping("/merchant-sites")
    fun registerMerchantSite(request: HttpServletRequest): ResponseEntity<ByteArray> = command(request, "RegisterMerchantSite") {
        MerchantOperationsRequestParser.site(it)?.let { c -> c.request to { onboarding.registerMerchantSite(c) } }
    }

    /** B1-2 계약 초안(Revision 1, 미승인 — 권리 입력 아님). 시작 소급 판정은 Domain(§3.4 `CONTRACT_START_RETROACTIVE`) — 이 adapter는 판정하지 않는다. */
    @PostMapping("/contracts")
    fun registerContract(request: HttpServletRequest): ResponseEntity<ByteArray> = command(request, "RegisterContract") {
        MerchantOperationsRequestParser.contract(it)?.let { c -> c.request to { onboarding.registerContract(c) } }
    }

    /** B1-2 서비스 계약(CSA) 추가. 같은 계약 같은 종류는 `SERVICE_KIND_DUPLICATE`(기존 CSA ID는 응답에 없다 — C-12). */
    @PostMapping("/service-agreements")
    fun addServiceAgreement(request: HttpServletRequest): ResponseEntity<ByteArray> = command(request, "AddServiceAgreement") {
        MerchantOperationsRequestParser.serviceAgreement(it)?.let { c -> c.request to { onboarding.addServiceAgreement(c) } }
    }

    /** permit(대기 0) → 엄격 해석 → Use Case → 응답. [parse]가 null이면 400(Use Case 미호출). */
    private fun command(
        request: HttpServletRequest,
        operation: String,
        parse: (ByteArray) -> Pair<AdminRequest, () -> AdminCommandResult>?,
    ): ResponseEntity<ByteArray> =
        permits.write(operation, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_WRITE_BUSY) }) {
            val parsed = parse(request.inputStream.readAllBytes())
            if (parsed == null) {
                log.info("operation={} requestId={} result=REQUEST_MALFORMED", operation, AdminChannelResponses.requestId(request))
                return@write AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
            }
            val (adminRequest, execute) = parsed
            try {
                respond(request, operation, adminRequest.correlationId, execute())
            } catch (e: AdminCommandFailure) {
                failed(request, operation, adminRequest.correlationId, e)
            } catch (e: RuntimeException) {
                // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값 — 등록번호·채널 식별값 — 이 섞일 수 있다). 분류(타입 이름)만.
                count(operation, FAILED)
                log.warn(
                    "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                    operation, AdminChannelResponses.requestId(request), adminRequest.correlationId, e.javaClass.simpleName,
                )
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private fun respond(request: HttpServletRequest, operation: String, correlationId: String, result: AdminCommandResult): ResponseEntity<ByteArray> {
        val (outcome, response) = when (result) {
            is AdminCommandResult.Succeeded -> SUCCEEDED to AdminChannelResponses.json(
                request, OK, linkedMapOf("outcome" to SUCCEEDED, "reference" to result.reference, "replayed" to result.replayed),
            )
            // C-12: 거절 reference는 여기서 버린다(키 자체를 만들지 않는다).
            is AdminCommandResult.Rejected -> REJECTED to AdminChannelResponses.json(
                request, OK, linkedMapOf("outcome" to REJECTED, "rejection" to result.rejection.name, "replayed" to result.replayed),
            )
            AdminCommandResult.IdempotencyKeyReused -> KEY_REUSED to AdminChannelResponses.error(request, CONFLICT, AdminChannelResponses.IDEMPOTENCY_KEY_REUSED)
        }
        count(operation, outcome)
        log.info("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, outcome)
        return response
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
        registry.counter(COMMAND_METRIC, "operation", operation, "outcome", outcome).increment()
    }

    companion object {
        const val COMMAND_METRIC = "paycore.master.admin_channel.command"
        private const val SUCCEEDED = "SUCCEEDED"
        private const val REJECTED = "REJECTED"
        private const val KEY_REUSED = "KEY_REUSED"
        private const val FAILED = "FAILED"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val CONFLICT = 409
        private const val SERVICE_UNAVAILABLE = 503
    }
}
