package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantLabel
import com.paycore.admin.merchantops.application.provided.MerchantAccountIdRules
import com.paycore.admin.merchantops.application.provided.MerchantIdRules
import com.paycore.admin.merchantops.application.provided.MerchantLabelFailure
import com.paycore.admin.merchantops.application.provided.MerchantLabelResult
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandKind
import com.paycore.admin.merchantops.application.required.MasterCommandResult
import com.paycore.admin.merchantops.application.required.MasterLabelGateway
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
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
 * [MasterOpsGateway] HTTP 구현(MERCHANT-OPS-001 §4). `RestClient` + JDK `HttpClient`(프록시 없음·redirect NEVER), connect·read timeout, 응답 본문은 256 KiB + 1바이트까지만 읽는다
 * (초과 = 해석 불가). **DB TX 안이면 즉시 `IllegalStateException`**. 전용 permit(대기 0 — 없으면 MASTER를 부르지 않고 `MASTER_OPS_BUSY`). 매 호출 새 시각 `t`로 서명,
 * 경로 변수·본문 값은 호출자가 형식 검사한 값만. 200 응답은 [MasterResponseShape](허용 필드 모양)로 검사해 다시 만든다 — 위반이면 `RESPONSE_UNREADABLE`.
 * 로그는 operation·결과 분류만(본문·서명·키·등록번호 없음).
 *
 * 등록 명령([command] — MERCHANT-OPS-002 §4.1): 같은 서명기·키(D-MOPS-005), **조회와 별개의 쓰기 permit**(대기 0 — 없으면 MASTER를 부르지 않고 [MasterCommandResult.Busy]), 응답 상한
 * 4 KiB, 200은 성공·거절 두 모양 중 하나와 **정확히** 같아야 한다(거절에 `reference`가 오면 해석 불가 — C-12 fail-closed). 409 `IDEMPOTENCY_KEY_REUSED` → KeyReused,
 * 503 `ADMIN_WRITE_BUSY` → Busy, 503 `REQUEST_IN_PROGRESS`·`OUTCOME_UNKNOWN`·`TEMPORARILY_UNAVAILABLE` → RetrySameKey, timeout·연결 끊김·해석 불가·그 밖 상태 → **Unknown**
 * (효과 유무를 모른다 — 효과 없음으로 단정하지 않는다). 로그에 MASTER 멱등 키·본문 값이 없다.
 *
 * 라벨 묶음([accountLabels] — TXN-LIST-ENRICH-001 §3.6·§4.2·§4.4): 같은 서명기·키, **라벨 전용 permit**(조회 permit과 별개 — 라벨 폭주가 승인 결정 조회·가맹점 화면을 막지 않는다, M-1),
 * **라벨 전용 HTTP client**(같은 connect timeout·프록시 없음·redirect NEVER, read timeout만 `label-read-timeout` — M-2), 응답 상한 64 KiB, 중복 JSON 키 거부(N-3 — 라벨 경로만),
 * 200은 [MasterResponseShape.MERCHANT_ACCOUNT_LABELS] + 이 요청의 답(보낸 MID의 부분집합·중복 없음·오름차순) — 하나라도 어긋나면 전부 UNREADABLE. 상한 초과는 §8대로 UNAVAILABLE `RESPONSE_TOO_LARGE`(리뷰 M-a). 로그는 분류·건수만.
 */
class HttpMasterOpsGateway(private val settings: MasterOpsSettings, private val signer: MasterCallSigner, private val clock: () -> Instant) : MasterOpsGateway, MasterLabelGateway {

    private val json: JsonMapper = JsonMapper.builder().build()
    private val labelJson: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val permits = Semaphore(settings.permits)
    private val writePermits = Semaphore(settings.writePermits)
    private val labelPermits = Semaphore(settings.labelPermits)
    private val client: RestClient = client(settings.readTimeout)
    private val labelClient: RestClient = client(settings.labelReadTimeout)

    private fun client(readTimeout: java.time.Duration): RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(settings.connectTimeout).build(),
            ).apply { setReadTimeout(readTimeout) },
        )
        .build()

    override fun accountLabels(merchantAccountIds: List<String>): MerchantLabelResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (TXN-LIST-ENRICH-001 §6)" }
        require(merchantAccountIds.size in 1..MerchantAccountIdRules.MAX_PER_CALL) { "1..${MerchantAccountIdRules.MAX_PER_CALL} merchant account ids" }
        require(merchantAccountIds.all { MerchantAccountIdRules.FORMAT.matches(it) } && merchantAccountIds.zipWithNext().all { (a, b) -> a < b }) { "distinct sorted MIDs" }
        return labelCall(ACCOUNT_LABELS, merchantAccountIds)
    }

    /**
     * MERCHANT-CODE-001 §5.6.3(MC-3b): 가맹점 내부 키 → 라벨. [accountLabels]와 **같은** 라벨 전용 permit·client(label-read-timeout)·64 KiB 상한·중복 JSON 키 거부(C-34 — 새 설정 키 없음),
     * 200은 [MasterResponseShape.MERCHANT_LABELS] + 대응 검사(보낸 키의 부분집합·중복 없음·오름차순 — 위반이면 그 묶음 전체 UNREADABLE, 틀린 상호가 다른 행에 붙지 않게).
     */
    override fun merchantLabels(merchantIds: List<String>): MerchantLabelResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (MERCHANT-CODE-001 §5.6.3)" }
        require(merchantIds.size in 1..MerchantIdRules.MAX_PER_CALL) { "1..${MerchantIdRules.MAX_PER_CALL} merchant ids" }
        require(merchantIds.all { MerchantIdRules.FORMAT.matches(it) } && merchantIds.zipWithNext().all { (a, b) -> a < b }) { "distinct sorted merchant ids" }
        return labelCall(MERCHANT_LABELS, merchantIds)
    }

    /** 라벨 묶음 한 종류(경로·본문 필드·응답 모양·항목 키 필드·로그 연산 이름). */
    private class LabelKind(val operation: String, val path: String, val requestField: String, val shape: MasterResponseShape, val keyField: String)

    private fun labelCall(kind: LabelKind, sent: List<String>): MerchantLabelResult {
        if (!labelPermits.tryAcquire()) return MerchantLabelResult.Unavailable(MerchantLabelFailure.BUSY, LABELS_BUSY).also { logLabels(kind, it) }
        try {
            val body = json.writeValueAsBytes(linkedMapOf(kind.requestField to sent))
            val result = when (val response = exchange(HttpMethod.POST, kind.path, body, MasterOpsSettings.LABEL_RESPONSE_MAX_BYTES, labelClient)) {
                is Raw.Failed -> MerchantLabelResult.Unavailable(MerchantLabelFailure.UNAVAILABLE, response.failure)
                // 리뷰 M-a(§8): 응답 상한 초과(readLimited → null)는 상태와 무관하게 UNAVAILABLE — 본문을 끝까지 읽지 않아 모양을 판정하지 않았으므로 UNREADABLE이 아니다.
                is Raw.Http -> if (response.body == null) MerchantLabelResult.Unavailable(MerchantLabelFailure.UNAVAILABLE, TOO_LARGE) else when (response.status) {
                    OK -> labels(kind, response.body, sent)?.let { MerchantLabelResult.Found(it) }
                        ?: MerchantLabelResult.Unavailable(MerchantLabelFailure.UNREADABLE, UNREADABLE)
                    SERVICE_UNAVAILABLE -> if (errorCode(response.body) == "ADMIN_READ_BUSY") {
                        MerchantLabelResult.Unavailable(MerchantLabelFailure.BUSY, "ADMIN_READ_BUSY")
                    } else {
                        MerchantLabelResult.Unavailable(MerchantLabelFailure.UNAVAILABLE, "HTTP_503")
                    }
                    else -> MerchantLabelResult.Unavailable(MerchantLabelFailure.UNAVAILABLE, "HTTP_${response.status}")
                }
            }
            logLabels(kind, result)
            return result
        } finally {
            labelPermits.release()
        }
    }

    /** 모양 + 이 요청의 답(전부 아니면 전무). 위반이면 null. */
    @Suppress("UNCHECKED_CAST")
    private fun labels(kind: LabelKind, body: ByteArray?, sent: List<String>): Map<String, MerchantLabel>? = runCatching {
        val node = labelJson.readTree(body?.takeIf { it.isNotEmpty() } ?: return null)
        val items = (kind.shape.read(node) as Map<String, Any?>)["items"] as List<Map<String, Any?>>
        val keys = items.map { it[kind.keyField] as String }
        val requested = sent.toSet()
        if (keys.size > sent.size || !keys.all { it in requested } || keys.zipWithNext().any { (a, b) -> a >= b }) return null
        items.associate { (it[kind.keyField] as String) to MerchantLabel(it["merchantName"] as String, it["merchantCode"] as String) }
    }.getOrNull()

    private fun logLabels(kind: LabelKind, result: MerchantLabelResult) {
        val text = when (result) {
            is MerchantLabelResult.Found -> "FOUND items=${result.labels.size}"
            is MerchantLabelResult.Unavailable -> "${result.failure}:${result.reason}"
        }
        log.info("operation=master-ops-{} result={}", kind.operation, text)
    }

    override fun search(criteria: MerchantSearchCriteria): MasterCallResult {
        val body = linkedMapOf<String, Any>()
        criteria.merchantId?.let { body["merchantId"] = it }
        criteria.businessId?.let { body["businessId"] = it }
        criteria.lifecycle?.let { body["lifecycle"] = it }
        criteria.registrationNumber?.let { body["registrationNumber"] = it }
        criteria.after?.let { body["after"] = it }
        criteria.merchantCode?.let { body["merchantCode"] = it }
        // MERCHANT-NAME-SEARCH-001 MNS-2: 원문 그대로(형식 검사한 값 — trim·LIKE 이스케이프는 하지 않는다, MASTER가 글자 그대로 앞부분 일치). 응답 모양은 목록(DIRECTORY).
        criteria.displayNamePrefix?.let { body["displayNamePrefix"] = it }
        criteria.limit?.let { body["limit"] = it }
        // MERCHANT-CODE-001 §3.8: 응답 모양은 **보낸 기준**으로 고른다(응답을 보고 고르지 않는다) — 코드 검색만 `matchedRetiredCode`가 있는 DIRECTORY_BY_CODE.
        val shape = if (criteria.merchantCode != null) MasterResponseShape.DIRECTORY_BY_CODE else MasterResponseShape.DIRECTORY
        return call("search", HttpMethod.POST, SEARCH_PATH, json.writeValueAsBytes(body), shape)
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

    /**
     * MERCHANT-OPS-002 B2-2 승인 실행·기한 확인(§9.4·§9.5 C-28 매핑). 저장 바이트 그대로 POST, 쓰기 permit(등록 명령과 공유 — 대기 0).
     * 200 성공·거절(§3.3 모양 — 거절에 reference 없음, C-12)·기한 확인 NOT_YET_DUE / 409 `IDEMPOTENCY_KEY_REUSED` → KeyReused / 400·413 → Malformed /
     * 401·404 → Unknown(alert) / 503·timeout·해석 불가·permit 없음 → Unknown. 로그에 본문·키·근거 참조가 없다.
     */
    override fun approval(operation: MerchantApprovalOperation, body: ByteArray, deadline: Boolean): MerchantApprovalResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (MERCHANT-OPS-002 §9.5)" }
        val name = if (deadline) "approval-deadline" else "approval-${operation.path}"
        if (!writePermits.tryAcquire()) return MerchantApprovalResult.Unknown(BUSY, alert = false).also { logApproval(name, it) }
        try {
            val path = if (deadline) "$COMMANDS_PATH/approval-deadline-confirmations" else "$COMMANDS_PATH/${operation.path}"
            val result = classify(exchange(HttpMethod.POST, path, body, MasterOpsSettings.COMMAND_RESPONSE_MAX_BYTES)) { node ->
                when {
                    deadline && node.has("decision") -> read(node, MasterResponseShape.NOT_YET_DUE)?.let { MerchantApprovalResult.NotYetDue }
                    node.get("outcome")?.takeIf { it.isString }?.asString() == "SUCCEEDED" -> read(node, MasterResponseShape.approvalSucceeded(operation.code))?.let {
                        MerchantApprovalResult.Decided(true, it["reference"] as String, null, it["replayed"] as Boolean)
                    }
                    else -> read(node, MasterResponseShape.COMMAND_REJECTED)?.let {
                        MerchantApprovalResult.Decided(false, null, it["rejection"] as String, it["replayed"] as Boolean)
                    }
                }
            }
            logApproval(name, result)
            return result
        } finally {
            writePermits.release()
        }
    }

    /**
     * 결정 조회(조회 permit — 쓰기 효과 없음). NONE / DECIDED(지문·경로 표식 포함).
     * B2-2 리뷰 n-4: DECIDED의 [MerchantApprovalResult.Decided.replayed]는 **항상 true**로 둔다 — 결정 조회 응답에는 `replayed` 필드가 없고, 조회가 돌려주는 결정은 정의상 이미 커밋된
     * 과거 결정(이 호출이 만든 효과가 아님)이기 때문이다. 결과 반영은 replayed 값을 쓰지 않는다(지문·operation 대조와 outcome만 쓴다).
     */
    override fun approvalDecision(approvalId: String): MerchantApprovalResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "MASTER is never called inside a database transaction (MERCHANT-OPS-002 §9.5)" }
        require(APPROVAL_ID.matches(approvalId)) { "approvalId" }
        if (!permits.tryAcquire()) return MerchantApprovalResult.Unknown(BUSY, alert = false).also { logApproval("approval-decision", it) }
        try {
            val body = """{"approvalId":"$approvalId","correlationId":"mop.$approvalId"}""".toByteArray(Charsets.UTF_8)
            val result = classify(exchange(HttpMethod.POST, "$COMMANDS_PATH/approval-decisions", body, MasterOpsSettings.COMMAND_RESPONSE_MAX_BYTES)) { node ->
                when (node.get("decision")?.takeIf { it.isString }?.asString()) {
                    "NONE" -> read(node, MasterResponseShape.DECISION_NONE)?.let { MerchantApprovalResult.None }
                    "DECIDED" -> if (node.get("outcome")?.takeIf { it.isString }?.asString() == "SUCCEEDED") {
                        val code = node.get("operation")?.takeIf { it.isString }?.asString()
                        // minor-4: operation 허용 목록 밖이면 해석 불가(Unknown — fail-closed), 안이면 그 operation의 reference 문법으로 검사.
                        code?.takeIf { it in MasterResponseShape.APPROVAL_OPERATIONS }?.let { read(node, MasterResponseShape.decisionSucceeded(it)) }?.let {
                            MerchantApprovalResult.Decided(true, it["reference"] as String, null, true, it["operation"] as String, it["approvalFingerprint"] as String, it["decidedVia"] as String)
                        }
                    } else {
                        read(node, MasterResponseShape.DECISION_REJECTED)?.let {
                            MerchantApprovalResult.Decided(false, null, it["rejection"] as String, true, it["operation"] as String, it["approvalFingerprint"] as String, it["decidedVia"] as String)
                        }
                    }
                    else -> null
                }
            }
            logApproval("approval-decision", result)
            return result
        } finally {
            permits.release()
        }
    }

    /** C-28 상태 분류(200 본문은 [ok]가 모양 검사 — null이면 해석 불가). */
    private fun classify(raw: Raw, ok: (JsonNode) -> MerchantApprovalResult?): MerchantApprovalResult = when (raw) {
        is Raw.Failed -> MerchantApprovalResult.Unknown(raw.failure, alert = false)
        is Raw.Http -> when (raw.status) {
            OK -> tree(raw.body)?.takeIf { it.isObject }?.let(ok) ?: MerchantApprovalResult.Unknown(UNREADABLE, alert = false)
            CONFLICT -> if (errorCode(raw.body) == "IDEMPOTENCY_KEY_REUSED") MerchantApprovalResult.KeyReused else MerchantApprovalResult.Unknown("HTTP_409", alert = true)
            BAD_REQUEST -> MerchantApprovalResult.Malformed(if (errorCode(raw.body) == "REQUEST_MALFORMED") "REQUEST_MALFORMED" else "HTTP_400")
            PAYLOAD_TOO_LARGE -> MerchantApprovalResult.Malformed("PAYLOAD_TOO_LARGE")
            UNAUTHORIZED -> MerchantApprovalResult.Unknown("MASTER_UNAUTHORIZED", alert = true)
            NOT_FOUND -> MerchantApprovalResult.Unknown("MASTER_NOT_FOUND", alert = true)
            SERVICE_UNAVAILABLE -> when (val code = errorCode(raw.body)) {
                "REQUEST_IN_PROGRESS", "OUTCOME_UNKNOWN", "TEMPORARILY_UNAVAILABLE", "ADMIN_WRITE_BUSY", "ADMIN_READ_BUSY" -> MerchantApprovalResult.Unknown(code, alert = false)
                else -> MerchantApprovalResult.Unknown("HTTP_503", alert = false)
            }
            else -> MerchantApprovalResult.Unknown("HTTP_${raw.status}", alert = false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(node: JsonNode, shape: MasterResponseShape): Map<String, Any?>? = runCatching { shape.read(node) as Map<String, Any?> }.getOrNull()

    private fun logApproval(operation: String, result: MerchantApprovalResult) {
        val text = when (result) {
            is MerchantApprovalResult.Decided -> if (result.succeeded) "SUCCEEDED" else "REJECTED:${result.rejection}"
            MerchantApprovalResult.None -> "NONE"
            MerchantApprovalResult.NotYetDue -> "NOT_YET_DUE"
            MerchantApprovalResult.KeyReused -> "KEY_REUSED"
            is MerchantApprovalResult.Malformed -> "MALFORMED:${result.code}"
            is MerchantApprovalResult.Unknown -> "UNKNOWN:${result.failure}"
        }
        log.info("operation=master-ops-{} result={}", operation, text)
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

    private fun exchange(method: HttpMethod, path: String, body: ByteArray, maxBytes: Int = MasterOpsSettings.RESPONSE_MAX_BYTES, via: RestClient = client): Raw = try {
        val request = via.method(method).uri(settings.baseUrl.resolve(path))
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
        val ACCOUNT_LABELS = LabelKind(
            "account-labels", "/internal/v1/admin/merchant-account-labels", "merchantAccountIds", MasterResponseShape.MERCHANT_ACCOUNT_LABELS, "merchantAccountId",
        )

        /** MERCHANT-CODE-001 §5.6.3(MC-3b) — 배포는 MASTER 먼저(ADMIN만 먼저면 MASTER 404 → UNAVAILABLE — 화면 "—", 목록 영향 없음, fail-safe). */
        val MERCHANT_LABELS = LabelKind("merchant-labels", "/internal/v1/admin/merchant-labels", "merchantIds", MasterResponseShape.MERCHANT_LABELS, "merchantId")
        const val LABELS_BUSY = "MASTER_LABELS_BUSY"
        const val TOO_LARGE = "RESPONSE_TOO_LARGE"
        const val CONFLICT = 409
        const val SERVICE_UNAVAILABLE = 503
        const val BUSY = "MASTER_OPS_BUSY"
        const val UNREADABLE = "RESPONSE_UNREADABLE"
        const val OK = 200
        const val NOT_FOUND = 404
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val PAYLOAD_TOO_LARGE = 413
        val APPROVAL_ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        const val MAX_CAUSE_DEPTH = 20
        val MERCHANT_ID = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}
