package com.paycore.admin.financialops.adapter.integration

import com.paycore.admin.financialops.application.provided.AcquisitionFileSearchCriteria
import com.paycore.admin.financialops.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.admin.financialops.application.required.AcquiringOpsGateway
import com.paycore.admin.financialops.application.required.FinancialCallResult
import com.paycore.admin.financialops.application.required.OperationCallResult
import com.paycore.admin.financialops.application.required.FinancialOpsGateway
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
 * [FinancialOpsGateway] HTTP 구현(ACQ-VIEW-001 §4.6 — merchantops `HttpMasterOpsGateway`와 같은 방식). `RestClient` + JDK `HttpClient`(프록시 없음·redirect NEVER), connect·read
 * timeout, 응답 본문은 128 KiB + 1바이트까지만 읽는다(초과 = 해석 불가). **DB TX 안이면 즉시 `IllegalStateException`**. 전용 permit(대기 0 — 없으면 FINANCIAL을 부르지 않고
 * `FINANCIAL_OPS_BUSY`). 매 호출 새 시각 `t`로 서명, 경로 변수·본문 값은 호출자가 형식 검사한 값만.
 * 200 응답은 [FinancialResponseShape](허용 필드 모양 — 중복 key 거부)로 검사해 다시 만들고, **요청-응답 대응**(TXN-OPS-001 MINOR-1과 같은 이유 — 모양이 맞아도 이 요청의 답이 아니면 해석
 * 불가)을 본다: 항목 수 ≤ limit, 정확 조회·매입사·환경·3축 상태 필터 일치, 업무일 ∈ 기간, cursor보다 뒤, needsAttention이면 사유 있음, 상세 fileId = 요청, 레코드 seq > afterSeq·상태 필터
 * 일치. **쪽 순서와 다음 cursor도 FINANCIAL keyset 그대로여야 한다**(B2 리뷰 MINOR-3 — `AcquisitionFileViewService`가 limit + 1행으로 정한다): 목록은 (업무일, 파일 ID) 내림차순·
 * `nextCursor`가 있으면 쪽이 가득(= limit)이고 마지막 항목과 같다, 레코드 쪽은 seq 오름차순·`nextCursor`가 있으면 쪽이 가득이고 마지막 seq와 같다, 상세의 레코드 첫 쪽은 기본 조건
 * (afterSeq 0·상태 없음·기본 쪽 크기 20)의 답이다. 위반이면 `RESPONSE_UNREADABLE`. 로그는 operation·결과 분류만(본문·서명·키·fileId 없음).
 */
class HttpFinancialOpsGateway(
    private val settings: FinancialOpsSettings,
    private val signer: FinancialCallSigner,
    private val operationSigner: FinancialCallSigner = FinancialCallSigner(settings.operationKeyId, settings.operationSigningKey()),
    private val clock: () -> Instant,
) : FinancialOpsGateway, AcquiringOpsGateway {

    private val json: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val permits = Semaphore(settings.permits)

    /** 조작 permit(ACQ-ADMIN-001 — 조회 permit과 분리: 조회 폭주가 승인 실행을 막지 않고, 조작 장애가 조회를 막지 않는다). */
    private val operationPermits = Semaphore(settings.operationPermits)

    // ------------------------------------------------------------------ ACQ-ADMIN-001 B1-A 조작 채널

    /** 미리 보기(조회 키): 모양 + 같은 파일 + 요청 순번과 같은 순번 집합(순서 무관, 중복 없음). */
    override fun targets(fileId: String, seqs: List<Int>): FinancialCallResult {
        require(FinancialResponseShape.FILE_ID_FORMAT.matches(fileId)) { "fileId" }
        require(seqs.isNotEmpty() && seqs.all { it >= 1 }) { "seqs" }
        val body = json.writeValueAsBytes(linkedMapOf("seqs" to seqs))
        return call("reacquisition-targets", HttpMethod.POST, "$FILES_PATH/$fileId/reacquisition-targets", body, FinancialResponseShape.TARGETS) { doc ->
            val got = items(doc).map { (it["seq"] as Long).toInt() }
            doc["fileId"] == fileId && got.size == seqs.size && got.toSet() == seqs.toSet()
        }
    }

    override fun execute(approvalId: String, body: ByteArray): OperationCallResult {
        require(APPROVAL_ID.matches(approvalId)) { "approvalId" }
        // B1-A 리뷰 n-3: 실행 POST 응답도 결정 조회·기한 확인처럼 본문 승인 ID가 이 승인이어야 결정으로 본다(아니면 결과 불명확 — 조회로만 수렴).
        return operation("ops-execute", HttpMethod.POST, OPS_EXECUTE_PATH, body, approvalId)
    }

    override fun decision(approvalId: String): OperationCallResult {
        require(APPROVAL_ID.matches(approvalId)) { "approvalId" }
        return operation("ops-decision", HttpMethod.GET, "$OPS_DECISIONS_PATH/$approvalId", ByteArray(0), approvalId)
    }

    override fun confirmDeadline(approvalId: String, body: ByteArray): OperationCallResult {
        require(APPROVAL_ID.matches(approvalId)) { "approvalId" }
        return operation("ops-deadline", HttpMethod.POST, "$OPS_DECISIONS_PATH/$approvalId/deadline-confirmation", body, approvalId)
    }

    /**
     * 조작 호출(조작 키·조작 permit). 200 본문: 결정 모양([FinancialResponseShape.OPS_DECISION] — 결정 모양 규칙: EXECUTED ⇔ 새 파일, REJECTED ⇔ code) 또는 결정 없음 모양.
     * [expectedApprovalId]가 있으면(경로 승인 ID) 본문 승인 ID가 같아야 한다(요청-응답 대응). 해석 불가는 Failed(`RESPONSE_UNREADABLE` — 결과 불명확).
     */
    private fun operation(operation: String, method: HttpMethod, path: String, body: ByteArray, expectedApprovalId: String? = null): OperationCallResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "FINANCIAL is never called inside a database transaction (ACQ-ADMIN-001 §3.1)" }
        if (!operationPermits.tryAcquire()) return OperationCallResult.Failed(OPERATION_BUSY).also { logOperation(operation, it) }
        try {
            val result = when (val response = exchange(method, path, body, operationSigner)) {
                is Raw.Failed -> OperationCallResult.Failed(response.failure)
                is Raw.Http -> if (response.status == OK) readOperation(response.body, expectedApprovalId) else OperationCallResult.Status(response.status, errorCode(response.body))
            }
            logOperation(operation, result)
            return result
        } finally {
            operationPermits.release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readOperation(body: ByteArray?, expectedApprovalId: String?): OperationCallResult {
        val node = tree(body) ?: return OperationCallResult.Failed(UNREADABLE)
        if (runCatching { FinancialResponseShape.OPS_NONE_LOOKUP.read(node) }.isSuccess) {
            return if (expectedApprovalId == null || node.get("approvalId").asString() == expectedApprovalId) OperationCallResult.NoDecision else OperationCallResult.Failed(UNREADABLE)
        }
        if (runCatching { FinancialResponseShape.OPS_NONE.read(node) }.isSuccess) return OperationCallResult.NoDecision
        val doc = runCatching { FinancialResponseShape.OPS_DECISION.read(node) as Map<String, Any?> }.getOrNull() ?: return OperationCallResult.Failed(UNREADABLE)
        val shapeOk = when (doc["decision"]) {
            "EXECUTED" -> doc["resultFileId"] != null && doc["code"] == null
            "NOT_REQUIRED" -> doc["resultFileId"] == null && doc["code"] == null
            else -> doc["resultFileId"] == null && doc["code"] != null
        }
        if (!shapeOk || (expectedApprovalId != null && doc["approvalId"] != expectedApprovalId)) return OperationCallResult.Failed(UNREADABLE)
        return OperationCallResult.Decision(doc)
    }

    private fun logOperation(operation: String, result: OperationCallResult) {
        log.info(
            "operation=financial-{} result={}", operation,
            when (result) {
                is OperationCallResult.Failed -> result.reason
                is OperationCallResult.Status -> "HTTP_${result.status}"
                is OperationCallResult.Decision -> "DECISION_${result.document["decision"]}"
                OperationCallResult.NoDecision -> "NONE"
            },
        )
    }
    private val client: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(settings.connectTimeout).build(),
            ).apply { setReadTimeout(settings.readTimeout) },
        )
        .build()

    override fun search(criteria: AcquisitionFileSearchCriteria): FinancialCallResult {
        val body = linkedMapOf<String, Any>()
        criteria.businessDateFrom?.let { body["businessDateFrom"] = it }
        criteria.businessDateTo?.let { body["businessDateTo"] = it }
        criteria.fileId?.let { body["fileId"] = it }
        criteria.acquirerCode?.let { body["acquirerCode"] = it }
        criteria.environment?.let { body["environment"] = it }
        criteria.transmissionState?.let { body["transmissionState"] = it }
        criteria.receiptState?.let { body["receiptState"] = it }
        criteria.resultState?.let { body["resultState"] = it }
        criteria.needsAttention?.let { body["needsAttention"] = it }
        criteria.after?.let { body["after"] = linkedMapOf("businessDate" to it.businessDate, "fileId" to it.fileId) }
        criteria.limit?.let { body["limit"] = it }
        return call("search", HttpMethod.POST, SEARCH_PATH, json.writeValueAsBytes(body), FinancialResponseShape.PAGE) { answersSearch(it, criteria) }
    }

    override fun detail(fileId: String): FinancialCallResult {
        require(FinancialResponseShape.FILE_ID_FORMAT.matches(fileId)) { "fileId" }
        return call("detail", HttpMethod.GET, "$FILES_PATH/$fileId", ByteArray(0), FinancialResponseShape.DETAIL) { doc ->
            @Suppress("UNCHECKED_CAST")
            (doc["file"] as Map<String, Any?>)["fileId"] == fileId && answersRecords(doc["records"] as Map<String, Any?>, AcquisitionRecordSearchCriteria())
        }
    }

    override fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria): FinancialCallResult {
        require(FinancialResponseShape.FILE_ID_FORMAT.matches(fileId)) { "fileId" }
        val body = linkedMapOf<String, Any>()
        criteria.status?.let { body["status"] = it }
        criteria.afterSeq?.let { body["afterSeq"] = it }
        criteria.limit?.let { body["limit"] = it }
        return call("records", HttpMethod.POST, "$FILES_PATH/$fileId/record-searches", json.writeValueAsBytes(body), FinancialResponseShape.RECORDS) {
            answersRecords(it, criteria)
        }
    }

    /** 레코드 쪽 대응: 수 ≤ limit, seq > afterSeq·상태 필터 일치, seq 오름차순, `nextCursor` = null 또는 (가득 찬 쪽의) 마지막 seq. */
    private fun answersRecords(doc: Map<String, Any?>, c: AcquisitionRecordSearchCriteria): Boolean {
        val items = items(doc)
        val limit = c.limit ?: DEFAULT_LIMIT
        val seqs = items.map { it["seq"] as Long }
        val next = doc["nextCursor"] as Long?
        return items.size <= limit && seqs.zipWithNext().all { (a, b) -> a < b } &&
            items.all { r -> (r["seq"] as Long) > (c.afterSeq ?: 0) && (c.status == null || r["status"] == c.status) } &&
            (next == null || (items.size == limit && next == seqs.last()))
    }

    private fun answersSearch(doc: Map<String, Any?>, c: AcquisitionFileSearchCriteria): Boolean {
        val items = items(doc)
        val limit = c.limit ?: DEFAULT_LIMIT
        if (items.size > limit) return false
        val keys = items.map { (it["businessDate"] as String) to (it["fileId"] as String) }
        // (업무일, 파일 ID) 내림차순 — 같은 정렬 키는 FINANCIAL ORDER BY와 같은 바이트 비교(utf8mb4_0900_bin, 두 값 모두 ASCII).
        if (!keys.zipWithNext().all { (a, b) -> a.first > b.first || (a.first == b.first && a.second > b.second) }) return false
        @Suppress("UNCHECKED_CAST")
        val next = doc["nextCursor"] as Map<String, Any?>?
        if (next != null && (items.size != limit || next["businessDate"] != keys.last().first || next["fileId"] != keys.last().second)) return false
        return items.all { i ->
            val date = i["businessDate"] as String
            val id = i["fileId"] as String
            (c.fileId == null || id == c.fileId) && (c.acquirerCode == null || i["acquirerCode"] == c.acquirerCode) &&
                (c.environment == null || i["environment"] == c.environment) && (c.transmissionState == null || i["transmissionState"] == c.transmissionState) &&
                (c.receiptState == null || i["receiptState"] == c.receiptState) && (c.resultState == null || i["resultState"] == c.resultState) &&
                (c.businessDateFrom == null || date >= c.businessDateFrom) && (c.businessDateTo == null || date <= c.businessDateTo) &&
                (c.after == null || date < c.after.businessDate || (date == c.after.businessDate && id < c.after.fileId)) &&
                (c.needsAttention != true || (i["attention"] as List<*>).isNotEmpty())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(doc: Map<String, Any?>): List<Map<String, Any?>> = doc["items"] as List<Map<String, Any?>>

    private fun call(
        operation: String,
        method: HttpMethod,
        path: String,
        body: ByteArray,
        shape: FinancialResponseShape,
        answers: (Map<String, Any?>) -> Boolean,
    ): FinancialCallResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "FINANCIAL is never called inside a database transaction (ACQ-VIEW-001 §4.6)" }
        if (!permits.tryAcquire()) return FinancialCallResult.Unavailable(BUSY).also { log(operation, it) }
        try {
            val result = when (val response = exchange(method, path, body, signer)) {
                is Raw.Failed -> FinancialCallResult.Unavailable(response.failure)
                is Raw.Http -> when (response.status) {
                    OK -> read(response.body, shape)?.takeIf { doc -> runCatching { answers(doc) }.getOrDefault(false) }?.let { FinancialCallResult.Found(it) }
                        ?: FinancialCallResult.Unavailable(UNREADABLE)
                    NOT_FOUND -> if (errorCode(response.body) == "ACQUISITION_FILE_NOT_FOUND") FinancialCallResult.NotFound else FinancialCallResult.Unavailable("HTTP_404")
                    else -> FinancialCallResult.Unavailable("HTTP_${response.status}")
                }
            }
            log(operation, result)
            return result
        } finally {
            permits.release()
        }
    }

    private fun log(operation: String, result: FinancialCallResult) {
        log.info("operation=financial-ops-{} result={}", operation, if (result is FinancialCallResult.Unavailable) result.reason else result.javaClass.simpleName)
    }

    private sealed interface Raw {
        class Http(val status: Int, val body: ByteArray?) : Raw

        class Failed(val failure: String) : Raw
    }

    private fun exchange(method: HttpMethod, path: String, body: ByteArray, by: FinancialCallSigner): Raw = try {
        val request = client.method(method).uri(settings.baseUrl.resolve(path))
            .header(FinancialCallSigner.HEADER, by.header(method.name(), path, clock().epochSecond, body))
            .accept(MediaType.APPLICATION_JSON)
        if (method == HttpMethod.POST) request.contentType(MediaType.APPLICATION_JSON).body(body)
        request.exchange { _, response -> Raw.Http(response.statusCode.value(), readLimited(response.body)) }
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
    private fun readLimited(stream: InputStream?): ByteArray? {
        if (stream == null) return ByteArray(0)
        val bytes = stream.readNBytes(FinancialOpsSettings.RESPONSE_MAX_BYTES + 1)
        return if (bytes.size > FinancialOpsSettings.RESPONSE_MAX_BYTES) null else bytes
    }

    private fun tree(body: ByteArray?): JsonNode? = body?.takeIf { it.isNotEmpty() }?.let { runCatching { json.readTree(it) }.getOrNull() }

    @Suppress("UNCHECKED_CAST")
    private fun read(body: ByteArray?, shape: FinancialResponseShape): Map<String, Any?>? =
        tree(body)?.let { node -> runCatching { shape.read(node) as Map<String, Any?> }.getOrNull() }

    private fun errorCode(body: ByteArray?): String? = tree(body)?.takeIf { it.isObject }?.get("error")?.takeIf { it.isString }?.asString()

    private companion object {
        val log = LoggerFactory.getLogger(HttpFinancialOpsGateway::class.java)
        const val SEARCH_PATH = "/internal/v1/admin/acquisition-file-searches"
        const val FILES_PATH = "/internal/v1/admin/acquisition-files"
        const val OPS_EXECUTE_PATH = "/internal/v1/admin/acquiring-operations/reacquisitions"
        const val OPS_DECISIONS_PATH = "/internal/v1/admin/acquiring-operations/decisions"
        const val OPERATION_BUSY = "FINANCIAL_OPERATION_BUSY"
        val APPROVAL_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")
        const val BUSY = "FINANCIAL_OPS_BUSY"
        const val UNREADABLE = "RESPONSE_UNREADABLE"
        const val DEFAULT_LIMIT = 20
        const val OK = 200
        const val NOT_FOUND = 404
        const val MAX_CAUSE_DEPTH = 20
    }
}
