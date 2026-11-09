package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandKind
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.Semaphore

/**
 * [MasterOpsGateway] HTTP 구현(MERCHANT-OPS-001 §4). `RestClient` + JDK `HttpClient`(프록시 없음·redirect NEVER), connect·read timeout, 응답 본문은 256 KiB + 1바이트까지만 읽는다
 * (초과 = 해석 불가). **DB TX 안이면 즉시 `IllegalStateException`**. 전용 permit(대기 0 — 없으면 MASTER를 부르지 않고 `MASTER_OPS_BUSY`). 매 호출 새 시각 `t`로 서명,
 * 경로 변수·본문 값은 호출자가 형식 검사한 값만. 200 응답은 [MasterResponseShape](허용 필드 모양)로 검사해 다시 만든다 — 위반이면 `RESPONSE_UNREADABLE`.
 * 로그는 operation·결과 분류만(본문·서명·키·등록번호 없음).
 *
 * 등록 명령([command] — MERCHANT-OPS-002 §4.1): 같은 서명기·키(D-MOPS-005), **조회와 별개의 쓰기 permit**(대기 0 — 없으면 MASTER를 부르지 않고 [MasterCommandResult.Busy]), 응답 상한
 * 4 KiB, 200은 성공·거절 두 모양 중 하나와 **정확히** 같아야 한다(거절에 `reference`가 오면 해석 불가 — C-12 fail-closed). 409 `IDEMPOTENCY_KEY_REUSED` → KeyReused,
 * 503 `ADMIN_WRITE_BUSY` → Busy, 503 `REQUEST_IN_PROGRESS`·`OUTCOME_UNKNOWN`·`TEMPORARILY_UNAVAILABLE` → RetrySameKey, timeout·연결 끊김·해석 불가·그 밖 상태 → **Unknown**
 * (효과 유무를 모른다 — 효과 없음으로 단정하지 않는다). 로그에 MASTER 멱등 키·본문 값이 없다.
 */
class HttpMasterOpsGateway(private val settings: MasterOpsSettings, private val signer: MasterCallSigner, private val clock: () -> Instant) : MasterOpsGateway {

    private val json: JsonMapper = JsonMapper.builder().build()
    private val permits = Semaphore(settings.permits)
    private val writePermits = Semaphore(settings.writePermits)
    private val client: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(settings.connectTimeout).build(),
            ).apply { setReadTimeout(settings.readTimeout) },
        )
        .build()

    override fun search(criteria: MerchantSearchCriteria): MasterCallResult {
        val body = linkedMapOf<String, Any>()
        criteria.merchantId?.let { body["merchantId"] = it }
        criteria.businessId?.let { body["businessId"] = it }
        criteria.lifecycle?.let { body["lifecycle"] = it }
        criteria.registrationNumber?.let { body["registrationNumber"] = it }
        criteria.after?.let { body["after"] = it }
        criteria.limit?.let { body["limit"] = it }
        return call("search", HttpMethod.POST, SEARCH_PATH, json.writeValueAsBytes(body), MasterResponseShape.DIRECTORY)
    }

    override fun detail(merchantId: String): MasterCallResult {
        require(MERCHANT_ID.matches(merchantId)) { "merchantId" }
        return call("detail", HttpMethod.GET, "$MERCHANTS_PATH/$merchantId", ByteArray(0), MasterResponseShape.DETAIL)
    }

    override fun history(merchantId: String, afterSeq: Long): MasterCallResult {
        require(MERCHANT_ID.matches(merchantId) && afterSeq >= 0) { "history arguments" }
        return call("history", HttpMethod.GET, "$MERCHANTS_PATH/$merchantId/history/after/$afterSeq", ByteArray(0), MasterResponseShape.HISTORY)
    }

    override fun command(command: MasterCommand): MasterCommandResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (MERCHANT-OPS-002 §4.1)" }
        val operation = "command-${command.kind.path}"
        if (!writePermits.tryAcquire()) return MasterCommandResult.Busy(BUSY).also { logCommand(operation, it) }
        try {
            val body = linkedMapOf<String, Any>("idempotencyKey" to command.idempotencyKey, "actor" to command.actor, "correlationId" to command.correlationId)
            // 순서 = 명령 종류의 필드 순서. 없는 선택 필드(계약 effectiveTo)는 키 자체를 보내지 않는다.
            command.kind.fields.forEach { name -> command.fields[name]?.let { body[name] = it } }
            val result = when (val response = exchange(HttpMethod.POST, "$COMMANDS_PATH/${command.kind.path}", json.writeValueAsBytes(body), MasterOpsSettings.COMMAND_RESPONSE_MAX_BYTES)) {
                is Raw.Failed -> MasterCommandResult.Unknown(response.failure)
                is Raw.Http -> when (response.status) {
                    OK -> decided(response.body, command.kind)
                    CONFLICT -> if (errorCode(response.body) == "IDEMPOTENCY_KEY_REUSED") MasterCommandResult.KeyReused else MasterCommandResult.Unknown("HTTP_409")
                    SERVICE_UNAVAILABLE -> when (val code = errorCode(response.body)) {
                        "ADMIN_WRITE_BUSY" -> MasterCommandResult.Busy(code)
                        "REQUEST_IN_PROGRESS", "OUTCOME_UNKNOWN", "TEMPORARILY_UNAVAILABLE" -> MasterCommandResult.RetrySameKey(code)
                        else -> MasterCommandResult.Unknown("HTTP_503")
                    }
                    else -> MasterCommandResult.Unknown("HTTP_${response.status}")
                }
            }
            logCommand(operation, result)
            return result
        } finally {
            writePermits.release()
        }
    }

    /** 200 본문 → 저장된 판정. 성공(명령 종류의 reference 접두사 포함)·거절 모양과 정확히 같지 않으면 Unknown(RESPONSE_UNREADABLE). */
    private fun decided(body: ByteArray?, kind: MasterCommandKind): MasterCommandResult {
        val node = tree(body)?.takeIf { it.isObject } ?: return MasterCommandResult.Unknown(UNREADABLE)
        val shape = when (node.get("outcome")?.takeIf { it.isString }?.asString()) {
            "SUCCEEDED" -> MasterResponseShape.commandSucceeded(kind)
            "REJECTED" -> MasterResponseShape.COMMAND_REJECTED
            else -> return MasterCommandResult.Unknown(UNREADABLE)
        }
        @Suppress("UNCHECKED_CAST")
        val read = runCatching { shape.read(node) as Map<String, Any?> }.getOrNull() ?: return MasterCommandResult.Unknown(UNREADABLE)
        return MasterCommandResult.Decided(
            MasterCommandResult.Outcome.valueOf(read["outcome"] as String), read["rejection"] as String?, read["reference"] as String?, read["replayed"] as Boolean,
        )
    }

    private fun logCommand(operation: String, result: MasterCommandResult) {
        val text = when (result) {
            is MasterCommandResult.Decided -> result.outcome.name
            MasterCommandResult.KeyReused -> "KEY_REUSED"
            is MasterCommandResult.Busy -> "BUSY:${result.reason}"
            is MasterCommandResult.RetrySameKey -> "RETRY_SAME_KEY:${result.reason}"
            is MasterCommandResult.Unknown -> "UNKNOWN:${result.reason}"
        }
        log.info("operation=master-ops-{} result={}", operation, text)
    }

    private fun call(operation: String, method: HttpMethod, path: String, body: ByteArray, shape: MasterResponseShape): MasterCallResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (MERCHANT-OPS-001 §4)" }
        if (!permits.tryAcquire()) return MasterCallResult.Unavailable(BUSY).also { log(operation, it) }
        try {
            val result = when (val response = exchange(method, path, body)) {
                is Raw.Failed -> MasterCallResult.Unavailable(response.failure)
                is Raw.Http -> when (response.status) {
                    OK -> read(response.body, shape)?.let { MasterCallResult.Found(it) } ?: MasterCallResult.Unavailable(UNREADABLE)
                    NOT_FOUND -> if (errorCode(response.body) == "MERCHANT_NOT_FOUND") MasterCallResult.NotFound else MasterCallResult.Unavailable("HTTP_404")
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

    private sealed interface Raw {
        class Http(val status: Int, val body: ByteArray?) : Raw

        class Failed(val failure: String) : Raw
    }

    private fun exchange(method: HttpMethod, path: String, body: ByteArray, maxBytes: Int = MasterOpsSettings.RESPONSE_MAX_BYTES): Raw = try {
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

    private companion object {
        val log = LoggerFactory.getLogger(HttpMasterOpsGateway::class.java)
        const val SEARCH_PATH = "/internal/v1/admin/merchant-searches"
        const val MERCHANTS_PATH = "/internal/v1/admin/merchants"
        const val COMMANDS_PATH = "/internal/v1/admin/merchant-operations"
        const val CONFLICT = 409
        const val SERVICE_UNAVAILABLE = 503
        const val BUSY = "MASTER_OPS_BUSY"
        const val UNREADABLE = "RESPONSE_UNREADABLE"
        const val OK = 200
        const val NOT_FOUND = 404
        const val MAX_CAUSE_DEPTH = 20
        val MERCHANT_ID = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}
