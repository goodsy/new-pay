package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterContractIntakeCommand
import com.paycore.admin.merchantops.application.required.MasterContractIntakeGateway
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestClient
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.Semaphore

/**
 * [MasterBusinessGateway] HTTP 구현(BUSINESS-PROFILE-001 §7.2 — C-17: 중첩 JSON 명령이라 문자열 전용 `MasterCommand`를 확장하지 않는다). 기존 [MasterOpsSettings](연결·timeout·permit 수)·
 * [MasterCallSigner](같은 키 — D-MOPS-005)를 쓰고 `RestClient` + JDK `HttpClient`(프록시 없음·redirect NEVER). **DB TX 안이면 즉시 `IllegalStateException`**.
 * 조회 permit·쓰기 permit(각각 대기 0 — 없으면 MASTER를 부르지 않는다)은 이 gateway 전용 Semaphore(크기 = `permits`·`write-permits`, 가맹점 gateway와 별개 — 가역 결정).
 * 응답 읽기 상한: 조회 32 KiB·명령 8 KiB(+1바이트까지만 읽는다 — 넘으면 해석 불가). 응답 JSON 중복 key 거부. 200은 [MasterResponseShape]의 사업자 모양과 정확히 같아야 한다(fail-closed).
 *
 * 명령 응답 매핑(§6.7·§7.3, BIZ-2 인계 ②③):
 * - 200 성공(`biz_`) / 거절 — 중복 거절은 `existing` 필수([MasterResponseShape.BUSINESS_DUPLICATE_REJECTED]), 다른 거절은 `existing`·`reference` 없음([MasterResponseShape.COMMAND_REJECTED]).
 * - 400 `BUSINESS_VALIDATION_FAILED` + fieldErrors(path 문법·code 집합 검사) → [MasterBusinessCommandResult.Invalid] — **효과 없음 확정**(OUTCOME_UNKNOWN 아님 — C-16 보충).
 * - 409 `IDEMPOTENCY_KEY_REUSED` → KeyReused. 503 `ADMIN_WRITE_BUSY` → Busy, 503 `REQUEST_IN_PROGRESS`·`OUTCOME_UNKNOWN`·`TEMPORARILY_UNAVAILABLE`(중복 요약 조회 실패 포함 — m-1) → RetrySameKey.
 * - timeout·연결 끊김·모양 위반(fieldErrors 문법 위반 포함)·그 밖 상태 → Unknown(효과 유무를 모른다 — 효과 없음으로 단정하지 않는다).
 * 목록 검색은 null 필드를 **보내지 않는다**(MASTER parser는 생략만 허용 — BIZ-2 인계 ④). 로그는 연산·결과 분류만(본문·서명·키·번호·이름 없음).
 */
class HttpMasterBusinessGateway(private val settings: MasterOpsSettings, private val signer: MasterCallSigner, private val clock: () -> Instant) :
    MasterBusinessGateway, MasterContractIntakeGateway {

    private val json: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val permits = Semaphore(settings.permits)
    private val writePermits = Semaphore(settings.writePermits)
    private val client: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(settings.connectTimeout).build(),
            ).apply { setReadTimeout(settings.readTimeout) },
        )
        .build()

    override fun search(criteria: BusinessSearchCriteria): MasterCallResult {
        val body = linkedMapOf<String, Any>()
        criteria.displayNamePrefix?.let { body["displayNamePrefix"] = it }
        criteria.businessType?.let { body["businessType"] = it }
        criteria.registrationStatus?.let { body["registrationStatus"] = it }
        criteria.createdFrom?.let { body["createdFrom"] = it }
        criteria.createdTo?.let { body["createdTo"] = it }
        criteria.after?.let { body["after"] = it }
        criteria.limit?.let { body["limit"] = it }
        return query("business-search", HttpMethod.POST, "$BASE/searches", json.writeValueAsBytes(body), MasterResponseShape.BUSINESS_LIST)
    }

    override fun lookup(registrationNumber: String): MasterCallResult {
        require(REGISTRATION_NUMBER.matches(registrationNumber)) { "registration number format" }
        val body = json.writeValueAsBytes(linkedMapOf("registrationNumber" to registrationNumber))
        return query("business-lookup", HttpMethod.POST, "$BASE/registration-number-lookups", body, MasterResponseShape.BUSINESS_LOOKUP)
    }

    override fun detail(businessId: String): MasterCallResult {
        require(BUSINESS_ID.matches(businessId)) { "businessId" }
        return query("business-detail", HttpMethod.GET, "$BASE/$businessId", ByteArray(0), MasterResponseShape.BUSINESS_DETAIL)
    }

    override fun contractIntakes(businessId: String): MasterCallResult {
        require(BUSINESS_ID.matches(businessId)) { "businessId" }
        return query("business-contract-intakes", HttpMethod.GET, "$BASE/$businessId/contract-intakes", ByteArray(0), MasterResponseShape.CONTRACT_INTAKE_SECTION)
    }

    override fun history(businessId: String, afterSeq: Long): MasterCallResult {
        require(BUSINESS_ID.matches(businessId) && afterSeq >= 0) { "history arguments" }
        return query("business-history", HttpMethod.GET, "$BASE/$businessId/history/after/$afterSeq", ByteArray(0), MasterResponseShape.BUSINESS_HISTORY)
    }

    override fun command(command: MasterBusinessCommand): MasterBusinessCommandResult {
        val body = linkedMapOf<String, Any?>("idempotencyKey" to command.idempotencyKey, "actor" to command.actor, "correlationId" to command.correlationId)
        body.putAll(command.body)
        return post("business-command-${command.operation.masterPath}", "$BASE/commands/${command.operation.masterPath}", body, ::decided, ::invalid)
    }

    override fun criticalChangeProposal(command: MasterBusinessCommand): MasterBusinessCommandResult {
        val body = linkedMapOf<String, Any?>("idempotencyKey" to command.idempotencyKey, "actor" to command.actor, "correlationId" to command.correlationId)
        body.putAll(command.body)
        return post("business-critical-change-proposal", "$BASE/commands/critical-change-proposals", body, ::proposalDecided, ::invalid)
    }

    override fun approvalState(businessId: String): MasterCallResult {
        require(BUSINESS_ID.matches(businessId)) { "businessId" }
        return query("business-approval-state", HttpMethod.GET, "$BASE/$businessId/approval-state", ByteArray(0), MasterResponseShape.BUSINESS_APPROVAL_STATE)
    }

    override fun proposalOriginal(businessId: String, proposalId: String): MasterCallResult {
        require(BUSINESS_ID.matches(businessId) && PROPOSAL_ID.matches(proposalId)) { "proposal arguments" }
        return query(
            "business-proposal-original", HttpMethod.GET, "$BASE/$businessId/critical-change-proposals/$proposalId/original", ByteArray(0),
            MasterResponseShape.BUSINESS_PROPOSAL_ORIGINAL, notFoundCode = "CHANGE_PROPOSAL_NOT_FOUND",
        )
    }

    override fun changeEvidence(businessId: String, historyId: Long): MasterCallResult {
        require(BUSINESS_ID.matches(businessId) && historyId >= 1) { "evidence arguments" }
        return query(
            "business-change-evidence", HttpMethod.GET, "$BASE/$businessId/business-change-evidence/$historyId", ByteArray(0), MasterResponseShape.BUSINESS_CHANGE_EVIDENCE,
            notFoundCode = "CHANGE_EVIDENCE_NOT_FOUND",
        )
    }

    /** 제안 200: 성공(제안 요약 엄격 모양) 또는 공통 거절 모양(existing 없음). 그 밖은 Unknown(RESPONSE_UNREADABLE). */
    @Suppress("UNCHECKED_CAST")
    private fun proposalDecided(body: ByteArray?): MasterBusinessCommandResult {
        val node = tree(body)?.takeIf { it.isObject } ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
        return when (node.get("outcome")?.takeIf { it.isString }?.asString()) {
            "SUCCEEDED" -> {
                val read = runCatching { MasterResponseShape.BUSINESS_PROPOSAL_SUCCEEDED.read(node) as Map<String, Any?> }.getOrNull()
                    ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
                val proposal = read["proposal"] as Map<String, Any?>
                if (proposal["proposalId"] != read["reference"]) return MasterBusinessCommandResult.Unknown("REFERENCE_MISMATCH")
                MasterBusinessCommandResult.Decided(true, read["reference"] as String, null, read["replayed"] as Boolean, null, proposal)
            }
            "REJECTED" -> {
                val read = runCatching { MasterResponseShape.COMMAND_REJECTED.read(node) as Map<String, Any?> }.getOrNull() ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
                MasterBusinessCommandResult.Decided(false, null, read["rejection"] as String, read["replayed"] as Boolean, null)
            }
            else -> MasterBusinessCommandResult.Unknown(UNREADABLE)
        }
    }

    /**
     * MERCHANT-FLOW-001 FLOW-2b 계약 접수 건 명령(§4B.7 — `POST …/contract-intakes/commands/{open|move|complete}`). 사업자 명령과 같은 permit·서명·상한·상태 매핑. 200은 성공(`biz_`)·
     * 접수 건 거절 code([MasterResponseShape.CONTRACT_INTAKE_REJECTIONS])만, 400은 접수 건 path 문법([MasterResponseShape.CONTRACT_INTAKE_VALIDATION_FAILED])만 — 그 밖은 Unknown(fail-closed).
     */
    override fun contractIntakeCommand(command: MasterContractIntakeCommand): MasterBusinessCommandResult {
        val body = linkedMapOf<String, Any?>("idempotencyKey" to command.idempotencyKey, "actor" to command.actor, "correlationId" to command.correlationId)
        body.putAll(command.body)
        return post(
            (if (command.operation.isContractEvent) "business-contract-command-" else "contract-intake-command-") + command.operation.masterPath,
            "$BASE/${command.operation.masterBase}/commands/${command.operation.masterPath}", body,
            ::contractIntakeDecided, ::contractIntakeInvalid,
        )
    }

    private fun post(
        operation: String,
        path: String,
        body: Map<String, Any?>,
        decide: (ByteArray?) -> MasterBusinessCommandResult,
        invalidOf: (ByteArray?) -> MasterBusinessCommandResult,
    ): MasterBusinessCommandResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (BUSINESS-PROFILE-001 §7.2)" }
        if (!writePermits.tryAcquire()) return MasterBusinessCommandResult.Busy(BUSY).also { logCommand(operation, it) }
        try {
            val result = when (val response = exchange(HttpMethod.POST, path, json.writeValueAsBytes(body), COMMAND_RESPONSE_MAX_BYTES)) {
                is Raw.Failed -> MasterBusinessCommandResult.Unknown(response.failure)
                is Raw.Http -> when (response.status) {
                    OK -> decide(response.body)
                    BAD_REQUEST -> invalidOf(response.body)
                    CONFLICT -> if (errorCode(response.body) == "IDEMPOTENCY_KEY_REUSED") MasterBusinessCommandResult.KeyReused else MasterBusinessCommandResult.Unknown("HTTP_409")
                    SERVICE_UNAVAILABLE -> when (val code = errorCode(response.body)) {
                        "ADMIN_WRITE_BUSY" -> MasterBusinessCommandResult.Busy(code)
                        "REQUEST_IN_PROGRESS", "OUTCOME_UNKNOWN", "TEMPORARILY_UNAVAILABLE" -> MasterBusinessCommandResult.RetrySameKey(code)
                        else -> MasterBusinessCommandResult.Unknown("HTTP_503")
                    }
                    else -> MasterBusinessCommandResult.Unknown("HTTP_${response.status}")
                }
            }
            logCommand(operation, result)
            return result
        } finally {
            writePermits.release()
        }
    }

    /** 계약 접수 건 200: 성공(reference `biz_`) 또는 접수 건 거절 code만(existing·reference 없음). 그 밖 모양은 Unknown(RESPONSE_UNREADABLE). */
    @Suppress("UNCHECKED_CAST")
    private fun contractIntakeDecided(body: ByteArray?): MasterBusinessCommandResult {
        val node = tree(body)?.takeIf { it.isObject } ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
        val shape = when (node.get("outcome")?.takeIf { it.isString }?.asString()) {
            "SUCCEEDED" -> MasterResponseShape.BUSINESS_COMMAND_SUCCEEDED
            "REJECTED" -> MasterResponseShape.CONTRACT_INTAKE_REJECTED
            else -> return MasterBusinessCommandResult.Unknown(UNREADABLE)
        }
        val read = runCatching { shape.read(node) as Map<String, Any?> }.getOrNull() ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
        return decidedOf(read)
    }

    /** 계약 접수 건 400: 접수 건 path 문법의 `BUSINESS_VALIDATION_FAILED`면 Invalid(효과 없음 확정), 그 밖은 Unknown. */
    @Suppress("UNCHECKED_CAST")
    private fun contractIntakeInvalid(body: ByteArray?): MasterBusinessCommandResult {
        val node = tree(body)?.takeIf { it.isObject } ?: return MasterBusinessCommandResult.Unknown("HTTP_400")
        if (node.get("error")?.takeIf { it.isString }?.asString() != "BUSINESS_VALIDATION_FAILED") return MasterBusinessCommandResult.Unknown("HTTP_400")
        val read = runCatching { MasterResponseShape.CONTRACT_INTAKE_VALIDATION_FAILED.read(node) as Map<String, Any?> }.getOrNull()
            ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
        return invalidOf(read["fieldErrors"] as List<Map<String, Any?>>)
    }

    /** 200 → 저장된 판정. 성공·중복 거절(existing 필수)·다른 거절(existing 없음) 모양과 정확히 같지 않으면 Unknown(RESPONSE_UNREADABLE). */
    @Suppress("UNCHECKED_CAST")
    private fun decided(body: ByteArray?): MasterBusinessCommandResult {
        val node = tree(body)?.takeIf { it.isObject } ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
        val outcome = node.get("outcome")?.takeIf { it.isString }?.asString()
        val shape = when {
            outcome == "SUCCEEDED" -> MasterResponseShape.BUSINESS_COMMAND_SUCCEEDED
            outcome == "REJECTED" && node.get("rejection")?.takeIf { it.isString }?.asString() == DUPLICATE -> MasterResponseShape.BUSINESS_DUPLICATE_REJECTED
            outcome == "REJECTED" -> MasterResponseShape.COMMAND_REJECTED
            else -> return MasterBusinessCommandResult.Unknown(UNREADABLE)
        }
        val read = runCatching { shape.read(node) as Map<String, Any?> }.getOrNull() ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
        return decidedOf(read)
    }

    /** 400: `BUSINESS_VALIDATION_FAILED` 모양(path 문법·code 집합)이면 Invalid(효과 없음 확정), 그 밖(REQUEST_MALFORMED·모양 위반)은 Unknown(fail-closed — 기존 명령 관례). */
    @Suppress("UNCHECKED_CAST")
    private fun invalid(body: ByteArray?): MasterBusinessCommandResult {
        val node = tree(body)?.takeIf { it.isObject } ?: return MasterBusinessCommandResult.Unknown("HTTP_400")
        if (node.get("error")?.takeIf { it.isString }?.asString() != "BUSINESS_VALIDATION_FAILED") return MasterBusinessCommandResult.Unknown("HTTP_400")
        val read = runCatching { MasterResponseShape.BUSINESS_VALIDATION_FAILED.read(node) as Map<String, Any?> }.getOrNull()
            ?: return MasterBusinessCommandResult.Unknown(UNREADABLE)
        return invalidOf(read["fieldErrors"] as List<Map<String, Any?>>)
    }

    private fun query(
        operation: String,
        method: HttpMethod,
        path: String,
        body: ByteArray,
        shape: MasterResponseShape,
        notFoundCode: String = "BUSINESS_NOT_FOUND",
    ): MasterCallResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (BUSINESS-PROFILE-001 §7.2)" }
        if (!permits.tryAcquire()) return MasterCallResult.Unavailable(BUSY).also { log(operation, it) }
        try {
            val result = when (val response = exchange(method, path, body, QUERY_RESPONSE_MAX_BYTES)) {
                is Raw.Failed -> MasterCallResult.Unavailable(response.failure)
                is Raw.Http -> when (response.status) {
                    OK -> read(response.body, shape)?.let { MasterCallResult.Found(it) } ?: MasterCallResult.Unavailable(UNREADABLE)
                    NOT_FOUND -> if (errorCode(response.body) == notFoundCode) MasterCallResult.NotFound else MasterCallResult.Unavailable("HTTP_404")
                    else -> MasterCallResult.Unavailable("HTTP_${response.status}")
                }
            }
            log(operation, result)
            return result
        } finally {
            permits.release()
        }
    }

    private fun log(operation: String, result: MasterCallResult) {
        log.info("operation=master-ops-{} result={}", operation, if (result is MasterCallResult.Unavailable) result.reason else result.javaClass.simpleName)
    }

    private fun logCommand(operation: String, result: MasterBusinessCommandResult) {
        val text = when (result) {
            is MasterBusinessCommandResult.Decided -> if (result.succeeded) "SUCCEEDED" else "REJECTED:${result.rejection}"
            is MasterBusinessCommandResult.Invalid -> "INVALID:${result.fieldErrors.size}"
            MasterBusinessCommandResult.KeyReused -> "KEY_REUSED"
            is MasterBusinessCommandResult.Busy -> "BUSY:${result.reason}"
            is MasterBusinessCommandResult.RetrySameKey -> "RETRY_SAME_KEY:${result.reason}"
            is MasterBusinessCommandResult.Unknown -> "UNKNOWN:${result.reason}"
        }
        log.info("operation=master-ops-{} result={}", operation, text)
    }

    private sealed interface Raw {
        class Http(val status: Int, val body: ByteArray?) : Raw

        class Failed(val failure: String) : Raw
    }

    private fun exchange(method: HttpMethod, path: String, body: ByteArray, maxBytes: Int): Raw = try {
        val request = client.method(method).uri(settings.baseUrl.resolve(path))
            .header(MasterCallSigner.HEADER, signer.header(method.name(), path, clock().epochSecond, body))
            .accept(MediaType.APPLICATION_JSON)
        if (method == HttpMethod.POST) request.contentType(MediaType.APPLICATION_JSON).body(body)
        request.exchange { _, response -> Raw.Http(response.statusCode.value(), readLimited(response.body, maxBytes)) }
    } catch (e: RuntimeException) {
        val chain = generateSequence<Throwable>(e) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        Raw.Failed(
            when {
                chain.any { it is HttpTimeoutException || it is java.net.SocketTimeoutException } -> "TIMEOUT"
                chain.any { it is ConnectException } -> "CONNECT_FAILED"
                else -> "CALL_FAILED"
            },
        )
    }

    /** 상한 + 1바이트까지만 읽는다 — 넘으면 null(해석 불가). */
    private fun readLimited(stream: InputStream?, maxBytes: Int): ByteArray? {
        if (stream == null) return ByteArray(0)
        val bytes = stream.readNBytes(maxBytes + 1)
        return if (bytes.size > maxBytes) null else bytes
    }

    private fun tree(body: ByteArray?): JsonNode? = body?.takeIf { it.isNotEmpty() }?.let { runCatching { json.readTree(it) }.getOrNull() }

    @Suppress("UNCHECKED_CAST")
    private fun read(body: ByteArray?, shape: MasterResponseShape): Map<String, Any?>? =
        tree(body)?.let { node -> runCatching { shape.read(node) as Map<String, Any?> }.getOrNull() }

    private fun errorCode(body: ByteArray?): String? = tree(body)?.takeIf { it.isObject }?.get("error")?.takeIf { it.isString }?.asString()

    companion object {
        private val log = LoggerFactory.getLogger(HttpMasterBusinessGateway::class.java)

        /**
         * BIZ-3 리뷰 NIT-3: 모양 검사를 통과한 값으로 결과 값을 만들되 **생성 중 불변식 위반도 해석 불가**로 둔다(`Unknown(RESPONSE_UNREADABLE)` — 예외가 Use Case로 새어
         * 결과 감사 행이 빠지는 일이 없다). 현재 모양 검사가 같은 조건을 보장해 도달하지 않지만 방어로 둔다.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun decidedOf(read: Map<String, Any?>): MasterBusinessCommandResult = runCatching {
            MasterBusinessCommandResult.Decided(
                read["outcome"] == "SUCCEEDED", read["reference"] as String?, read["rejection"] as String?, read["replayed"] as Boolean, read["existing"] as Map<String, Any?>?,
            )
        }.getOrElse { MasterBusinessCommandResult.Unknown(UNREADABLE) }

        /** NIT-3·NIT-4: 필드 오류 목록(1개 이상, 각 항목 §4.6 문법) → Invalid. 위반이면 Unknown(RESPONSE_UNREADABLE). */
        internal fun invalidOf(errors: List<Map<String, Any?>>): MasterBusinessCommandResult = runCatching {
            require(errors.isNotEmpty()) { "no field error" }
            MasterBusinessCommandResult.Invalid(errors.map { BusinessFieldError(it["path"] as String, it["code"] as String) })
        }.getOrElse { MasterBusinessCommandResult.Unknown(UNREADABLE) }
        const val BASE = "/internal/v1/admin/businesses"

        /** 응답 읽기 상한(§7.2 — 조회 32 KiB·명령 8 KiB). */
        const val QUERY_RESPONSE_MAX_BYTES = 32 * 1024
        const val COMMAND_RESPONSE_MAX_BYTES = 8 * 1024
        private const val DUPLICATE = "BUSINESS_REGISTRATION_NUMBER_DUPLICATE"
        private const val BUSY = "MASTER_OPS_BUSY"
        private const val UNREADABLE = "RESPONSE_UNREADABLE"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val SERVICE_UNAVAILABLE = 503
        private const val MAX_CAUSE_DEPTH = 20
        private val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
        private val PROPOSAL_ID = Regex("^bcp_[0-9A-HJKMNP-TV-Z]{26}$")
        private val REGISTRATION_NUMBER = Regex("^[0-9]{10}$")
    }
}
