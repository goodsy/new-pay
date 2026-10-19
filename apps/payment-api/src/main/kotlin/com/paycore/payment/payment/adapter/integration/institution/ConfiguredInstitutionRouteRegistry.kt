package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.InstitutionRouteProfile
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import com.paycore.payment.payment.application.required.RouteCapabilities
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.Base64

/**
 * 기관 Route 등록부(`paycore.authorization`, AUTH-001 §5.8 — **운영 기본값 없음, Demo 값**). 기동 격리 검사(§5.6 겹 2, AUTHN-001 §13.6과 같은 표):
 * - backend type은 `SIMULATOR`만(REAL VAN adapter 없음). 모르는 type·REAL → 기동 거부. `SIMULATOR`는 TEST 환경에만(LIVE: SIMULATOR → 거부).
 * - Route의 환경에 backend가 있어야 한다. host는 loopback 또는 [allowedHosts]만(네트워크 egress 허용 목록 — 배포 작업이 같은 목록을 쓴다).
 * - 프로토콜 `sim-van-1`만, HMAC 키 32바이트 이상(시험·Demo 키만 — 환경변수, 저장소에 운영 키 없음), 읽기 timeout ≤ 전체 deadline.
 * 모르는 routeRef는 [resolve]가 null(fail-closed). toString은 키를 내보내지 않는다.
 */
@ConfigurationProperties(prefix = "paycore.authorization")
class ConfiguredInstitutionRouteRegistry(
    val backends: Map<String, Backend>,
    val routes: Map<String, Route>,
    val allowedHosts: List<String> = emptyList(),
) : InstitutionRouteResolver {

    class Backend(val type: String) {
        override fun toString(): String = "Backend(type=$type)"
    }

    class Capabilities(
        val confirmsNoEffect: Boolean,
        val closableAfter: Duration,
        val inquiryRetention: Duration,
        val sameIdResendSafe: Boolean,
        val authenticationEvidenceReusableForResend: Boolean,
        val supportedCurrencies: List<String>,
        val supportedCardTypes: List<String>,
        val installmentCardTypes: List<String>,
        val installmentMonths: List<Int>,
        /** 할부 최소금액(minor, 통화는 Route 지원 통화 — Demo). 결제창 Plan Demo 정책의 최소금액과 같아야 한다(AU-31·n-7). null = 최소금액 없음. */
        val installmentMinimumAmount: Long?,
    )

    class Route(
        val environment: String,
        val institution: String,
        val protocolVersion: String,
        val profileVersion: String,
        val host: String,
        val port: Int,
        val keyId: String,
        keyBase64: String,
        val connectTimeout: Duration,
        val readTimeout: Duration,
        val totalDeadline: Duration,
        val connectRetryBudget: Int,
        val capabilities: Capabilities,
    ) {
        /** HMAC 키(adapter 내부 전용). */
        internal val key: ByteArray = runCatching { Base64.getDecoder().decode(keyBase64) }.getOrElse { throw IllegalArgumentException("route key must be base64") }

        override fun toString(): String = "Route(environment=$environment, institution=$institution, protocolVersion=$protocolVersion, host=$host, port=$port, keyId=$keyId)"
    }

    private val profiles: Map<String, InstitutionRouteProfile>

    init {
        val environments = backends.map { (env, backend) ->
            val environment = Environment.entries.firstOrNull { it.name == env } ?: throw IllegalArgumentException("unknown environment for an authorization backend")
            require(backend.type == SIMULATOR) { "authorization backend type ${backend.type} is not available (only SIMULATOR; no REAL VAN adapter)" }
            require(environment == Environment.TEST) { "a SIMULATOR authorization backend is allowed only for TEST payments (LIVE refused)" }
            environment
        }.toSet()
        profiles = routes.mapValues { (routeRef, r) ->
            require(ROUTE_REF.matches(routeRef)) { "routeRef must be 1..64 of [A-Za-z0-9._-]" }
            val environment = Environment.entries.firstOrNull { it.name == r.environment } ?: throw IllegalArgumentException("unknown route environment")
            require(environment in environments) { "route $routeRef has no authorization backend for its environment" }
            require(r.protocolVersion == PROTOCOL) { "route $routeRef protocol must be $PROTOCOL" }
            require(r.host in LOOPBACK || r.host in allowedHosts) { "route $routeRef host must be loopback or in allowed-hosts" }
            require(r.port in 1..MAX_PORT) { "route $routeRef port must be 1..65535" }
            require(r.key.size >= MIN_KEY_BYTES) { "route $routeRef HMAC key must be at least $MIN_KEY_BYTES bytes" }
            require(r.keyId.isNotEmpty() && r.keyId.length <= MAX_KEY_ID && r.keyId.all { it in '!'..'~' }) { "route $routeRef key id must be printable ASCII" }
            require(r.connectRetryBudget >= 0) { "route $routeRef connect retry budget must not be negative" }
            listOf(r.connectTimeout, r.readTimeout, r.totalDeadline).forEach { require(!it.isNegative && !it.isZero) { "route $routeRef timeouts must be positive" } }
            require(r.readTimeout <= r.totalDeadline) { "route $routeRef read timeout must not exceed the total deadline" }
            require(r.institution.isNotEmpty() && r.profileVersion.isNotEmpty()) { "route $routeRef institution and profile version must be set" }
            // D-AUTH1-007: profile version은 Attempt(route_profile_version VARCHAR(64))에 고정되는 비교 키 — Capability를 바꾸면 이 값도 바꾼다(§20).
            require(PROFILE_VERSION.matches(r.profileVersion)) { "route $routeRef profile version must be 1..64 printable ASCII" }
            val c = r.capabilities
            InstitutionRouteProfile(
                routeRef = routeRef,
                environment = environment,
                institution = r.institution,
                protocolVersion = r.protocolVersion,
                profileVersion = r.profileVersion,
                capabilities = RouteCapabilities(
                    confirmsNoEffect = c.confirmsNoEffect,
                    closableAfter = c.closableAfter,
                    inquiryRetention = c.inquiryRetention,
                    sameIdResendSafe = c.sameIdResendSafe,
                    authenticationEvidenceReusableForResend = c.authenticationEvidenceReusableForResend,
                    supportedCurrencies = c.supportedCurrencies.map(::CurrencyCode).toSet(),
                    supportedCardTypes = c.supportedCardTypes.map(CardType::valueOf).toSet(),
                    installmentCardTypes = c.installmentCardTypes.map(CardType::valueOf).toSet(),
                    installmentMonths = c.installmentMonths.toSet().also { m -> require(m.all { it in 2..99 }) { "installment months must be 2..99" } },
                    installmentMinimumAmount = c.installmentMinimumAmount?.also { require(it >= 1) { "installment minimum amount must be positive" } },
                ),
            )
        }
    }

    override fun resolve(routeRef: String): InstitutionRouteProfile? = profiles[routeRef]

    /** Adapter 내부: 연결 정보(host·port·키·timeout). 모르는 routeRef는 null. */
    internal fun connection(routeRef: String): Route? = routes[routeRef]?.takeIf { routeRef in profiles }

    override fun toString(): String = "ConfiguredInstitutionRouteRegistry(backends=$backends, routes=$routes)"

    private companion object {
        const val SIMULATOR = "SIMULATOR"
        const val PROTOCOL = "sim-van-1"
        const val MIN_KEY_BYTES = 32
        const val MAX_KEY_ID = 64
        const val MAX_PORT = 65_535
        val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")
        val ROUTE_REF = Regex("^[A-Za-z0-9._-]{1,64}$")
        val PROFILE_VERSION = Regex("^[!-~]{1,64}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConfiguredInstitutionRouteRegistry::class)
class InstitutionRouteRegistryConfiguration
