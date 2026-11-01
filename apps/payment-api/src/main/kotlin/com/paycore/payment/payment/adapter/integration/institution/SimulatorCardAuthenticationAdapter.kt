package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.CardAuthenticationPort
import com.paycore.payment.payment.application.required.CardAuthenticationRegistration
import com.paycore.payment.payment.application.required.RegistrationFailure
import com.paycore.payment.payment.application.required.RegistrationResult
import com.paycore.payment.payment.domain.AuthenticatedDetails
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.LookupFailure
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ProviderAuthenticationResult
import com.paycore.payment.payment.domain.ProviderBindingEcho
import com.paycore.payment.payment.domain.ProviderOutcome
import com.paycore.payment.payment.domain.ProviderResultLookup
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * [CardAuthenticationPort]의 유일한 구현(AUTHN-001 §13.5, D-AUTHN-004 — REAL 없음, ArchUnit). HTTP로 가상 인증기관 계약 v1(golden)을 부른다.
 * - `RestClient`를 직접 구성하고 요청 factory는 JDK `HttpClient`(connect/read timeout 설정, redirect 따르지 않음) — Boot `RestClient.Builder` 자동 구성 미사용,
 *   새 의존성 없음(AN-25). 요청·응답 본문을 로그에 쓰지 않는다(§12.6). 응답 본문은 한도 + 1바이트까지만 읽는다(검토 #2 n-5 — 넘으면 MALFORMED).
 * - 응답은 닫힌 필드 집합으로 엄격 파싱한다(m-E: AUTHENTICATED ⇔ 증거 6필드 전부, 그 밖 outcome은 하나도 없음). 위반·타입 오류·모르는 필드는
 *   Unverifiable(MALFORMED)이다 — 검증 실패를 성공으로 처리하지 않는다(AC1 §6-4). HTTP 404는 NOT_FOUND(R3 — 상태 불변, m-5).
 * - cardType은 열린 토큰을 그대로 넘긴다(지원 판정은 domain R10 — m-4). state echo는 digest로 바꿔 넘긴다(원문은 domain에 들어가지 않는다).
 * - 업무 예외를 던지지 않는다(AC1 §9.1). domain 상태 전이를 부르지 않는다(ArchUnit institutionAdapterDoesNotMutateDomain).
 */
@Component
class SimulatorCardAuthenticationAdapter(settings: AuthenticationSettings, private val registry: MeterRegistry) : CardAuthenticationPort {

    private val connection = settings.simulator
    private val client: RestClient = RestClient.builder()
        .baseUrl(connection.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connection.connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build())
                .also { it.setReadTimeout(connection.readTimeout) },
        )
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${connection.clientKey}")
        .build()

    override fun register(request: CardAuthenticationRegistration): RegistrationResult = registerOnce(request).also { result ->
        val outcome = when (result) {
            is RegistrationResult.Registered -> "REGISTERED"
            is RegistrationResult.Failed -> result.reason.name
        }
        registry.counter(REGISTRATIONS_METRIC, "outcome", outcome).increment()
    }

    /** 조회 1회: 지연 timer + outcome counter(FOUND·Unverifiable 분류 — label에 ID 없음, §15). */
    override fun lookup(resultRef: String): ProviderResultLookup {
        val sample = Timer.start(registry)
        val result = lookupOnce(resultRef)
        sample.stop(registry.timer(LOOKUP_TIMER))
        val outcome = when (result) {
            is ProviderResultLookup.Found -> "FOUND"
            is ProviderResultLookup.Unverifiable -> result.reason.name
        }
        registry.counter(LOOKUPS_METRIC, "outcome", outcome).increment()
        return result
    }

    private fun registerOnce(request: CardAuthenticationRegistration): RegistrationResult = try {
        client.post().uri("/sim/v1/authentications").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(registrationBody(request))
            .exchange({ _, response ->
                val status = response.statusCode
                val body = cappedBody(response)
                when {
                    status.value() == 200 || status.value() == 201 -> parseRegistration(body)
                    status.is4xxClientError -> RegistrationResult.Failed(RegistrationFailure.REJECTED)
                    else -> RegistrationResult.Failed(RegistrationFailure.SERVER_ERROR)
                }
            }, false)
    } catch (e: ResourceAccessException) {
        RegistrationResult.Failed(if (isTimeout(e)) RegistrationFailure.TIMEOUT else RegistrationFailure.CONNECTION)
    } catch (e: RestClientException) {
        RegistrationResult.Failed(RegistrationFailure.SERVER_ERROR)
    }

    private fun lookupOnce(resultRef: String): ProviderResultLookup {
        if (!RESULT_REF.matches(resultRef)) return ProviderResultLookup.Unverifiable(LookupFailure.MALFORMED)
        return try {
            client.get().uri("/sim/v1/authentication-results/{resultRef}", resultRef).accept(MediaType.APPLICATION_JSON)
                .exchange({ _, response -> classifyLookup(response.statusCode, cappedBody(response), resultRef) }, false)
        } catch (e: ResourceAccessException) {
            ProviderResultLookup.Unverifiable(if (isTimeout(e)) LookupFailure.TIMEOUT else LookupFailure.CONNECTION)
        } catch (e: RestClientException) {
            // 그 밖의 client 오류도 결과 타입으로(검토 #2 n-5 — Port는 업무 예외를 던지지 않는다).
            ProviderResultLookup.Unverifiable(LookupFailure.SERVER_ERROR)
        }
    }

    /**
     * 본문을 한도 + 1바이트까지만 읽고 응답 stream을 닫는다(검토 #2 n-5). `exchange(…, close = false)`로 부르는 이유: Spring의 응답 close는 연결 재사용을 위해 남은 본문을
     * 끝까지 읽어 버린다(drain) — 한도를 넘긴 본문이나 끝을 늦게 닫는 상대 때문에 요청 스레드가 묶이지 않게 stream을 바로 닫아 연결을 버린다.
     */
    private fun cappedBody(response: ClientHttpResponse): ByteArray = response.body.use { it.readNBytes(MAX_BODY_BYTES + 1) }

    private fun classifyLookup(status: HttpStatusCode, body: ByteArray, resultRef: String): ProviderResultLookup = when {
        status.value() == 200 -> parseLookup(body, resultRef)?.let { ProviderResultLookup.Found(it) } ?: ProviderResultLookup.Unverifiable(LookupFailure.MALFORMED)
        status.value() == 404 -> ProviderResultLookup.Unverifiable(LookupFailure.NOT_FOUND)
        status.is5xxServerError -> ProviderResultLookup.Unverifiable(LookupFailure.SERVER_ERROR)
        else -> ProviderResultLookup.Unverifiable(LookupFailure.MALFORMED)
    }

    private fun registrationBody(r: CardAuthenticationRegistration): String {
        val b = r.binding
        return nodes.objectNode().also { o ->
            o.put("paymentId", b.paymentId.value)
            o.put("merchantRef", b.orderKey.merchantAccountId.value)
            o.put("environment", b.orderKey.environment.name)
            o.put("amountMinor", b.money.amountMinor)
            o.put("currency", b.money.currency.value)
            val plan = o.putObject("plan")
            when (val p = b.plan) {
                PaymentPlan.LumpSum -> plan.put("kind", "LUMP_SUM")
                is PaymentPlan.Installment -> plan.put("kind", "INSTALLMENT").put("months", p.months)
            }
            o.put("cardCompany", b.cardCompany)
            o.put("state", r.state)
            o.put("returnUrl", r.returnUrl)
        }.toString()
    }

    private fun parseRegistration(body: ByteArray): RegistrationResult {
        val root = tree(body) ?: return RegistrationResult.Failed(RegistrationFailure.MALFORMED)
        if (!root.isObject || names(root) != REGISTRATION_FIELDS) return RegistrationResult.Failed(RegistrationFailure.MALFORMED)
        val id = text(root, "authenticationTransactionId")?.takeIf { TRANSACTION_ID.matches(it) }
        val customerUrl = text(root, "customerUrl")
        if (id == null || customerUrl != "${connection.customerBaseUrl}/sim/authenticate/$id" || text(root, "expiresAt")?.let(::instant) == null) {
            return RegistrationResult.Failed(RegistrationFailure.MALFORMED)
        }
        return RegistrationResult.Registered(id, customerUrl)
    }

    /** 계약 위반이면 null(→ MALFORMED). */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun parseLookup(body: ByteArray, resultRef: String): ProviderAuthenticationResult? {
        val root = tree(body) ?: return null
        if (!root.isObject) return null
        val outcome = text(root, "outcome")?.let { o -> ProviderOutcome.entries.firstOrNull { it.name == o } } ?: return null
        val expected = if (outcome == ProviderOutcome.AUTHENTICATED) BASE_FIELDS + EVIDENCE_FIELDS else BASE_FIELDS
        if (names(root) != expected) return null
        if (text(root, "resultRef") != resultRef) return null
        val transactionRef = text(root, "authenticationTransactionId")?.takeIf { TRANSACTION_ID.matches(it) } ?: return null
        val state = text(root, "state")?.takeIf { STATE.matches(it) } ?: return null
        val binding = binding(root.get("binding")) ?: return null
        val validUntil = text(root, "validUntil")?.let(::instant) ?: return null
        val details = if (outcome == ProviderOutcome.AUTHENTICATED) {
            val cardType = text(root, "cardType")?.takeIf { CARD_TYPE.matches(it) } ?: return null
            // D-INST-CODE-001(INST-CODE-001 IC-5): 발급사·매입사·인증 기관 코드는 기관 식별 코드 공통 형식 — 밖이면 계약 위반(MALFORMED).
            val codes = listOf("issuerCode", "acquirerCode", "providerCode").map { text(root, it)?.takeIf(InstitutionCodeFormat::matches) ?: return null }
            val evidenceRef = text(root, "evidenceRef")?.takeIf { v -> v.length in 1..MAX_CODE } ?: return null
            val at = text(root, "authenticatedAt")?.let(::instant) ?: return null
            AuthenticatedDetails(cardType, codes[0], codes[1], codes[2], evidenceRef, at)
        } else {
            null
        }
        return runCatching {
            ProviderAuthenticationResult(transactionRef, outcome, SecretDigest.of(SecretKind.AUTHENTICATION_STATE, state), binding, details, validUntil)
        }.getOrNull()
    }

    @Suppress("ReturnCount")
    private fun binding(node: JsonNode?): ProviderBindingEcho? {
        if (node == null || !node.isObject || names(node) != BINDING_FIELDS) return null
        val amount = node.get("amountMinor")
        if (!amount.isIntegralNumber || !amount.canConvertToLong()) return null
        val planNode = node.get("plan")
        if (!planNode.isObject) return null
        val plan = when (text(planNode, "kind")) {
            "LUMP_SUM" -> if (names(planNode) == setOf("kind")) PaymentPlan.LumpSum else return null
            "INSTALLMENT" -> planNode.get("months")?.takeIf { it.isInt && names(planNode) == setOf("kind", "months") }?.let { m ->
                runCatching { PaymentPlan.Installment(m.intValue()) }.getOrNull()
            } ?: return null
            else -> return null
        }
        return runCatching {
            ProviderBindingEcho(
                paymentId = PaymentId(checkNotNull(text(node, "paymentId"))),
                environment = Environment.valueOf(checkNotNull(text(node, "environment"))),
                merchantAccountId = MerchantAccountId(checkNotNull(text(node, "merchantRef"))),
                money = Money(amount.longValue(), CurrencyCode(checkNotNull(text(node, "currency")))),
                plan = plan,
                cardCompany = checkNotNull(text(node, "cardCompany")).also { require(it.isNotEmpty()) },
            )
        }.getOrNull()
    }

    private fun tree(body: ByteArray): JsonNode? = try {
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) null else mapper.readTree(String(body, Charsets.UTF_8))
    } catch (e: JacksonException) {
        null
    }

    private fun names(node: JsonNode): Set<String> = node.properties().map { it.key }.toSet()

    private fun text(node: JsonNode, field: String): String? = node.get(field)?.takeIf { it.isString }?.stringValue()

    private fun instant(raw: String): Instant? = try {
        if (TIMESTAMP.matches(raw)) Instant.from(DateTimeFormatter.ISO_INSTANT.parse(raw)) else null
    } catch (e: DateTimeParseException) {
        null
    }

    private fun isTimeout(e: Throwable): Boolean =
        generateSequence(e) { c -> c.cause?.takeIf { it !== c } }.take(MAX_CAUSE_DEPTH).any {
            (it is HttpTimeoutException && it !is HttpConnectTimeoutException) || it is java.net.SocketTimeoutException
        } && generateSequence(e) { c -> c.cause?.takeIf { it !== c } }.take(MAX_CAUSE_DEPTH).none { it is ConnectException || it is HttpConnectTimeoutException }

    private companion object {
        const val MAX_BODY_BYTES = 16 * 1024
        const val REGISTRATIONS_METRIC = "paycore.authentication.registrations"
        const val LOOKUPS_METRIC = "paycore.authentication.lookups"
        const val LOOKUP_TIMER = "paycore.authentication.lookup.duration"
        const val MAX_CODE = 128
        const val MAX_CAUSE_DEPTH = 20
        val nodes: JsonNodeFactory = JsonNodeFactory.instance
        val mapper: JsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
        val REGISTRATION_FIELDS = setOf("authenticationTransactionId", "customerUrl", "expiresAt")
        val BASE_FIELDS = setOf("resultRef", "authenticationTransactionId", "outcome", "state", "binding", "validUntil")
        val EVIDENCE_FIELDS = setOf("cardType", "issuerCode", "acquirerCode", "providerCode", "evidenceRef", "authenticatedAt")
        val BINDING_FIELDS = setOf("paymentId", "merchantRef", "environment", "amountMinor", "currency", "plan", "cardCompany")
        val RESULT_REF = Regex("^srr_[A-Za-z0-9_-]{22}$")
        val TRANSACTION_ID = Regex("^sat_[A-Za-z0-9_-]{22}$")
        val STATE = Regex("^[A-Za-z0-9_-]{43}$")
        val CARD_TYPE = Regex("^[A-Z][A-Z0-9_]{0,31}$")
        val TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
    }
}
