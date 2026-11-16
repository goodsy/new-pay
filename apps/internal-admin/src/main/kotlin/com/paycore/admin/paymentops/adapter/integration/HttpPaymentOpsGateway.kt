package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.DecisionContent
import com.paycore.admin.paymentops.application.provided.DecisionListItem
import com.paycore.admin.paymentops.application.provided.DecisionPage
import com.paycore.admin.paymentops.application.provided.DecisionResult
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.PaymentTargetView
import com.paycore.admin.paymentops.application.provided.TargetResult
import com.paycore.admin.paymentops.application.provided.TransactionSearchCriteria
import com.paycore.admin.paymentops.application.required.HomeKpiGateway
import com.paycore.admin.paymentops.application.required.PaymentOpsGateway
import com.paycore.admin.paymentops.application.required.PaymentReadGateway
import com.paycore.admin.paymentops.application.required.PaymentReadResult
import com.paycore.admin.paymentops.domain.OwnerResponseClassifier
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
 * [PaymentOpsGateway] HTTP 구현(ADMIN-PAYMENT-OPS-001 §4.2·§4.7·§4.15). `RestClient` + `JdkClientHttpRequestFactory`(프록시 없음 — 시스템 속성 프록시 미사용,
 * redirect NEVER — I-11), connect·read timeout, 응답 본문은 64 KiB + 1바이트까지만 읽는다(초과 = 해석 불가 → UNKNOWN, L-4).
 * - **DB TX 안이면 즉시 `IllegalStateException`**(외부 호출 TX 밖 — `PaymentCallOutsideTransactionSpec`).
 * - permit은 용도별 `Semaphore`(대기 0 — 없으면 PAYMENT를 부르지 않고 Unavailable/Unknown `PAYMENT_OPS_BUSY`).
 * - 서명은 매 호출 새 시각 `t`(같은 본문 재전송도 새 서명), 경로 변수는 호출자가 형식 검사한 값만.
 * 로그는 operation·결과 분류만(본문·서명·키 없음 — §6).
 * TXN-OPS-001: 거래 조회 Port([PaymentReadGateway])도 이 객체가 구현한다 — 같은 서명 키·같은 `lookup` permit·같은 timeout·응답 상한(새 채널·새 키 없음, R-3).
 */
class HttpPaymentOpsGateway(private val settings: PaymentOpsSettings, private val signer: AdminCallSigner, private val clock: () -> Instant) :
    PaymentOpsGateway, PaymentReadGateway, HomeKpiGateway {

    // 리뷰 NIT: 응답의 중복 key는 어느 값을 믿을지 정할 수 없다 — 해석 오류(→ 해석 불가). 요청 본문 쓰기에는 영향 없음.
    private val json: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val approvalPermits = Semaphore(settings.permits.approval)
    private val lookupPermits = Semaphore(settings.permits.lookup)
    private val executionPermits = Semaphore(settings.permits.execution)
    private val client: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(settings.connectTimeout).build(),
            ).apply { setReadTimeout(settings.readTimeout) },
        )
        .build()

    override fun target(paymentId: String, purpose: CallPurpose): TargetResult {
        outsideTransaction()
        val permits = if (purpose == CallPurpose.LOOKUP) lookupPermits else approvalPermits
        if (!permits.tryAcquire()) return TargetResult.Unavailable(BUSY)
        try {
            val response = call(HttpMethod.GET, "/internal/v1/admin/payments/$paymentId/operator-cancel-target", ByteArray(0))
            return when (response) {
                is Raw.Failed -> TargetResult.Unavailable(response.failure)
                is Raw.Http -> when (response.status) {
                    OK -> parseTarget(response.body, paymentId)?.let { TargetResult.Found(it) } ?: TargetResult.Unavailable(UNREADABLE)
                    NOT_FOUND -> if (errorCode(response.body) == "PAYMENT_NOT_FOUND") TargetResult.NotFound else TargetResult.Unavailable("HTTP_404")
                    else -> TargetResult.Unavailable("HTTP_${response.status}")
                }
            }.also { log.info("operation=payment-ops-target purpose={} result={}", purpose, it.javaClass.simpleName) }
        } finally {
            permits.release()
        }
    }

    override fun execute(approvalId: String, body: ByteArray): ExecutionResult {
        outsideTransaction()
        if (!executionPermits.tryAcquire()) return ExecutionResult.Unknown(BUSY, null, false)
        try {
            val classification = when (val response = call(HttpMethod.POST, EXECUTE_PATH, body)) {
                is Raw.Failed -> OwnerResponseClassifier.unknown(response.failure)
                is Raw.Http -> if (response.body == null) {
                    OwnerResponseClassifier.unknown(UNREADABLE)
                } else {
                    val node = readTree(response.body)
                    val cancellationId = node?.get("cancellationId")?.takeIf { it.isString }?.asString()?.takeIf { CANCELLATION_ID.matches(it) }
                    val c = OwnerResponseClassifier.classify(response.status, node?.get("error")?.takeIf { it.isString }?.asString(), cancellationId != null)
                    if (c.kind == OwnerResponseClassifier.Kind.STARTED) {
                        log.info("operation=payment-ops-execute approvalId={} result=STARTED", approvalId)
                        return ExecutionResult.Started(cancellationId!!, node["existing"]?.takeIf { it.isBoolean }?.asBoolean() == true)
                    }
                    c
                }
            }
            log.info("operation=payment-ops-execute approvalId={} result={} code={}", approvalId, classification.kind, classification.code)
            return when (classification.kind) {
                OwnerResponseClassifier.Kind.OWNER_REJECTED -> ExecutionResult.OwnerRejected(classification.code!!, classification.alert, classification.operationalAlert)
                else -> ExecutionResult.Unknown(classification.code ?: UNREADABLE, classification.integrityFlag, classification.alert || classification.operationalAlert)
            }
        } finally {
            executionPermits.release()
        }
    }

    /** 기록 결정 조회(§4.3 — execution pool, 서명 GET, 본문 없음). */
    override fun decision(approvalId: String): DecisionResult = decisionCall(HttpMethod.GET, "$EXECUTE_PATH/$approvalId", ByteArray(0), "operation=payment-ops-decision")

    /** 기한 확인 전용 호출(§4.4.5 — 저장 본문 그대로, 기한 확인 경로로 서명 — 실행 서명과 바꿔 쓸 수 없다). */
    override fun confirmDeadline(approvalId: String, body: ByteArray): DecisionResult =
        decisionCall(HttpMethod.POST, "$EXECUTE_PATH/$approvalId/deadline-confirmation", body, "operation=payment-ops-deadline")

    /** 대사 결정 목록(§4.17 — 서명 GET, watermark는 경로 변수 10진 — query 없음, execution pool). 형식이 하나라도 어긋나면 Unavailable(이번 주기 건너뜀, watermark 불변). */
    override fun decisionsAfter(watermark: Long): DecisionPage {
        outsideTransaction()
        require(watermark >= 0) { "watermark" }
        if (!executionPermits.tryAcquire()) return DecisionPage.Unavailable(BUSY)
        try {
            return when (val response = call(HttpMethod.GET, "/internal/v1/admin/operator-decisions/after/$watermark", ByteArray(0))) {
                is Raw.Failed -> DecisionPage.Unavailable(response.failure)
                is Raw.Http -> if (response.status == OK) parsePage(response.body) ?: DecisionPage.Unavailable(UNREADABLE) else DecisionPage.Unavailable("HTTP_${response.status}")
            }.also { log.info("operation=payment-ops-decision-list result={}", it.javaClass.simpleName) }
        } finally {
            executionPermits.release()
        }
    }

    /**
     * 거래 검색(TXN-OPS-001 §3.1 — 서명 POST, 본문은 형식 검사된 키만 정해진 순서로, `lookup` pool). 200은 [PaymentResponseShape.LIST]로 검사해 다시 만든다(위반 = 해석 불가).
     */
    override fun searchPayments(criteria: TransactionSearchCriteria): PaymentReadResult {
        val body = linkedMapOf<String, Any>()
        criteria.createdFrom?.let { body["createdFrom"] = it }
        criteria.createdTo?.let { body["createdTo"] = it }
        criteria.environment?.let { body["environment"] = it }
        criteria.merchantAccountId?.let { body["merchantAccountId"] = it }
        criteria.merchantOrderId?.let { body["merchantOrderId"] = it }
        criteria.paymentId?.let { body["paymentId"] = it }
        criteria.status?.let { body["status"] = it }
        if (criteria.afterCreatedAt != null && criteria.afterPaymentId != null) body["after"] = linkedMapOf("createdAt" to criteria.afterCreatedAt, "paymentId" to criteria.afterPaymentId)
        criteria.limit?.let { body["limit"] = it }
        return read("search", HttpMethod.POST, PAYMENT_SEARCH_PATH, json.writeValueAsBytes(body), PaymentResponseShape.LIST) { answers(criteria, it) }
    }

    /**
     * 리뷰 MINOR-1: 모양이 맞아도 **이 요청의 답**인지 확인한다(아니면 해석 불가 — fail-closed). 항목 수 ≤ 요청 limit(없으면 서버 기본 20), 결제 ID 정확 조회는 그 결제 0..1건, 준 환경·MID·
     * 주문번호·상태와 같은 항목만, 기간을 주면 `createdAt ∈ [from, to)`, cursor를 주면 `(createdAt, paymentId) < cursor`, 주문명 미리보기와 잘림 여부가 서로 맞음(잘렸으면 정확히 10 code point,
     * 미리보기가 없으면 잘림 아님).
     */
    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
    private fun answers(c: TransactionSearchCriteria, doc: Map<String, Any?>): Boolean {
        val items = doc["items"] as List<Map<String, Any?>>
        if (items.size > (c.limit ?: DEFAULT_SEARCH_LIMIT)) return false
        if (c.paymentId != null && (items.size > 1 || items.any { it["paymentId"] != c.paymentId })) return false
        val from = c.createdFrom?.let(Instant::parse)
        val to = c.createdTo?.let(Instant::parse)
        val cursorAt = c.afterCreatedAt?.let(Instant::parse)
        fun same(item: Map<String, Any?>, key: String, expected: String?) = expected == null || item[key] == expected
        return items.all { item ->
            val at = Instant.parse(item["createdAt"] as String)
            val preview = item["orderNamePreview"] as String?
            val truncated = item["orderNameTruncated"] as Boolean
            same(item, "environment", c.environment) && same(item, "merchantAccountId", c.merchantAccountId) && same(item, "merchantOrderId", c.merchantOrderId) &&
                same(item, "status", c.status) && (from == null || at >= from) && (to == null || at < to) &&
                (cursorAt == null || at < cursorAt || (at == cursorAt && (item["paymentId"] as String) < c.afterPaymentId!!)) &&
                (if (preview == null) !truncated else !truncated || preview.codePointCount(0, preview.length) == ORDER_NAME_PREVIEW)
        }
    }

    /** 거래 상세(§3.4 — 서명 GET, 경로 변수는 형식 검사된 paymentId만). 응답의 paymentId가 요청과 다르면 해석 불가. */
    override fun paymentDetail(paymentId: String): PaymentReadResult {
        require(PAYMENT_ID.matches(paymentId)) { "paymentId" }
        return read("detail", HttpMethod.GET, "$PAYMENTS_PATH/$paymentId", ByteArray(0), PaymentResponseShape.DETAIL) { doc ->
            (doc["payment"] as? Map<*, *>)?.get("paymentId") == paymentId
        }
    }

    /**
     * 운영 홈 오늘 KPI(ADMIN-HOME-KPI-001 §4.2·§4.3 — 서명 GET, 본문·query 없음, 같은 키·`lookup` permit·timeout·응답 상한). 200은 [PaymentResponseShape.HOME_KPI] 모양 +
     * [HomeKpiAnswer] 이 요청의 답 검사(위반 = 해석 불가). 이 경로에 결제 없음(404)은 의미가 없어 Unavailable `HTTP_404`로 두고, 로그도 그 실제 결과로 남긴다(리뷰 NIT-1).
     */
    override fun homeKpis(): PaymentReadResult =
        read("home-kpi", HttpMethod.GET, HOME_KPI_PATH, ByteArray(0), PaymentResponseShape.HOME_KPI, paymentNotFoundMeaningful = false, belongs = HomeKpiAnswer::belongs)

    private fun read(
        operation: String,
        method: HttpMethod,
        path: String,
        body: ByteArray,
        shape: PaymentResponseShape,
        paymentNotFoundMeaningful: Boolean = true,
        belongs: (Map<String, Any?>) -> Boolean,
    ): PaymentReadResult {
        outsideTransaction()
        if (!lookupPermits.tryAcquire()) return PaymentReadResult.Unavailable(BUSY).also { readLog(operation, it) }
        try {
            val result = when (val response = call(method, path, body)) {
                is Raw.Failed -> PaymentReadResult.Unavailable(response.failure)
                is Raw.Http -> when (response.status) {
                    OK -> shaped(response.body, shape)?.takeIf { doc -> runCatching { belongs(doc) }.getOrDefault(false) }?.let { PaymentReadResult.Found(it) } ?: PaymentReadResult.Unavailable(UNREADABLE)
                    NOT_FOUND ->
                        if (paymentNotFoundMeaningful && errorCode(response.body) == "PAYMENT_NOT_FOUND") PaymentReadResult.NotFound else PaymentReadResult.Unavailable("HTTP_404")
                    else -> PaymentReadResult.Unavailable("HTTP_${response.status}")
                }
            }
            readLog(operation, result)
            return result
        } finally {
            lookupPermits.release()
        }
    }

    /** 로그는 연산·결과 분류만(MID·주문번호·결제 ID·주문명·서명 없음). */
    private fun readLog(operation: String, result: PaymentReadResult) {
        log.info("operation=payment-ops-read-{} result={}", operation, if (result is PaymentReadResult.Unavailable) result.reason else result.javaClass.simpleName)
    }

    @Suppress("UNCHECKED_CAST")
    private fun shaped(body: ByteArray?, shape: PaymentResponseShape): Map<String, Any?>? =
        body?.takeIf { it.isNotEmpty() }?.let { bytes -> runCatching { shape.read(json.readTree(bytes)) as Map<String, Any?> }.getOrNull() }

    private fun parsePage(body: ByteArray?): DecisionPage? = runCatching {
        val n = readTree(body) ?: return null
        val items = n.get("items")?.takeIf { it.isArray } ?: return null
        val next = n.get("nextWatermark")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it >= 0 } ?: return null
        val serverTime = java.time.Instant.parse(n.get("serverTime")?.takeIf { it.isString }?.asString() ?: return null)
        DecisionPage.Page(items.toList().map(::parseItem), next, serverTime)
    }.getOrNull()

    private fun parseItem(n: JsonNode): DecisionListItem {
        fun text(name: String): String = n.get(name)?.takeIf { it.isString }?.asString() ?: error("type")
        fun long(name: String): Long = n.get(name)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong() ?: error("type")
        val decision = text("decision").also { require(it == "STARTED" || it == "REJECTED") }
        return DecisionListItem(
            seq = long("seq"), approvalId = text("approvalId").also { require(it.isNotBlank() && it.length <= MAX_REF) }, decision = decision,
            code = if (decision == "REJECTED") text("code").also { require(CODE.matches(it)) } else null,
            cancellationId = if (decision == "STARTED") text("cancellationId").also { require(CANCELLATION_ID.matches(it)) } else null,
            content = DecisionContent(text("paymentId"), long("amountMinor"), text("currency"), text("requestedBy"), text("approvedBy"), text("approvedTargetFingerprint")),
            recordedAt = java.time.Instant.parse(text("recordedAt")), origin = text("origin"),
        )
    }

    private fun decisionCall(method: HttpMethod, path: String, body: ByteArray, operation: String): DecisionResult {
        outsideTransaction()
        if (!executionPermits.tryAcquire()) return DecisionResult.Unavailable(BUSY)
        try {
            val result = when (val response = call(method, path, body)) {
                is Raw.Failed -> DecisionResult.Unavailable(response.failure)
                is Raw.Http -> if (response.status == OK) parseDecision(response.body) ?: DecisionResult.Unavailable(UNREADABLE) else DecisionResult.Unavailable("HTTP_${response.status}")
            }
            log.info("{} result={}", operation, if (result is DecisionResult.Recorded) result.decision else result.javaClass.simpleName)
            return result
        } finally {
            executionPermits.release()
        }
    }

    /** 결정 응답(golden ③): NONE / STARTED(pcn_ 취소 ID) / REJECTED(code) + 공통 내용 필드. 형식이 하나라도 어긋나면 null(해석 불가 → 상태 유지). */
    private fun parseDecision(body: ByteArray?): DecisionResult? = runCatching {
        val n = readTree(body) ?: return null
        fun text(name: String): String = n.get(name)?.takeIf { it.isString }?.asString() ?: error("type")
        fun long(name: String): Long = n.get(name)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong() ?: error("type")
        val confirmed = n.get("confirmed")?.let { if (it.isBoolean) it.asBoolean() else error("type") } ?: false
        when (text("decision")) {
            "NONE" -> DecisionResult.None
            "STARTED", "REJECTED" -> {
                val decision = text("decision")
                val content = DecisionContent(
                    text("paymentId"), long("amountMinor"), text("currency"), text("requestedBy"), text("approvedBy"), text("approvedTargetFingerprint"),
                )
                val cancellationId = if (decision == "STARTED") text("cancellationId").also { require(CANCELLATION_ID.matches(it)) } else null
                val code = if (decision == "REJECTED") text("code").also { require(CODE.matches(it)) } else null
                DecisionResult.Recorded(decision, code, cancellationId, content, confirmed)
            }
            else -> null
        }
    }.getOrNull()

    private sealed interface Raw {
        class Http(val status: Int, val body: ByteArray?) : Raw

        class Failed(val failure: String) : Raw
    }

    private fun call(method: HttpMethod, path: String, body: ByteArray): Raw = try {
        val request = client.method(method).uri(settings.baseUrl.resolve(path))
            .header(AdminCallSigner.HEADER, signer.header(method.name(), path, clock().epochSecond, body))
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
        val bytes = stream.readNBytes(PaymentOpsSettings.RESPONSE_MAX_BYTES + 1)
        return if (bytes.size > PaymentOpsSettings.RESPONSE_MAX_BYTES) null else bytes
    }

    private fun readTree(body: ByteArray?): JsonNode? = body?.takeIf { it.isNotEmpty() }?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    private fun errorCode(body: ByteArray?): String? = readTree(body)?.get("error")?.takeIf { it.isString }?.asString()

    private fun parseTarget(body: ByteArray?, paymentId: String): PaymentTargetView? = runCatching {
        val n = readTree(body) ?: return null
        fun text(name: String): String? = n.get(name)?.let { if (it.isNull) null else if (it.isString) it.asString() else error("type") }
        fun long(name: String): Long? = n.get(name)?.let { if (it.isNull) null else if (it.isIntegralNumber && it.canConvertToLong()) it.asLong() else error("type") }
        fun bool(name: String): Boolean = n.get(name)?.takeIf { it.isBoolean }?.asBoolean() ?: error("type")
        PaymentTargetView(
            paymentId = requireNotNull(text("paymentId")).also { require(it == paymentId) { "other payment" } },
            paymentStatus = requireNotNull(text("paymentStatus")).also { require(STATUS.matches(it)) },
            cancellable = bool("cancellable"),
            currency = text("currency")?.also { require(CURRENCY.matches(it)) },
            authorizedMinor = long("authorizedMinor")?.also { require(it >= 0) },
            cancellableMinor = requireNotNull(long("cancellableMinor")).also { require(it >= 0) },
            balanceRevision = requireNotNull(long("balanceRevision")).also { require(it >= 0) },
            onHold = bool("onHold"),
            inFlightCancel = bool("inFlightCancel"),
        )
    }.getOrNull()

    private fun outsideTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "PAYMENT is never called inside a database transaction (ADMIN-PAYMENT-OPS-001 §4.7)" }
    }

    private companion object {
        val log = LoggerFactory.getLogger(HttpPaymentOpsGateway::class.java)
        const val EXECUTE_PATH = "/internal/v1/admin/operator-cancellations"
        const val PAYMENT_SEARCH_PATH = "/internal/v1/admin/payment-searches"
        const val PAYMENTS_PATH = "/internal/v1/admin/payments"
        const val HOME_KPI_PATH = "/internal/v1/admin/home-kpis"
        val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        const val DEFAULT_SEARCH_LIMIT = 20
        const val ORDER_NAME_PREVIEW = 10
        const val BUSY = "PAYMENT_OPS_BUSY"
        const val UNREADABLE = "RESPONSE_UNREADABLE"
        const val OK = 200
        const val NOT_FOUND = 404
        const val MAX_CAUSE_DEPTH = 20
        val CANCELLATION_ID = Regex("^pcn_[0-9A-HJKMNP-TV-Z]{26}$")
        val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
        const val MAX_REF = 64
        val STATUS = Regex("^[A-Z][A-Z_]{0,31}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}
