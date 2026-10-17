package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.application.required.AuthenticationProfile
import com.paycore.payment.payment.application.required.AuthenticationProfiles
import com.paycore.payment.payment.application.required.CardAuthenticationEndpoints
import com.paycore.payment.payment.application.required.CardCompanyOption
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentPlanPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 인증 backend 설정(`paycore.authentication`, AUTHN-001 §13.6 — **운영 기본값 없음**, 시험·로컬은 Demo). 기동 시 격리 검사(D-AUTHN-004, Coordinator 승인
 * "TEST 외 환경" = Payment environment 축):
 * - backend type은 `SIMULATOR`만(REAL Adapter 없음 — AC1 §9.4). 모르는 type·REAL → 기동 거부.
 * - `SIMULATOR`는 TEST 환경에만(LIVE: SIMULATOR → 기동 거부). LIVE에 backend가 없으면 LIVE 결제창은 422 CHECKOUT_NOT_SUPPORTED.
 * - backend가 가리키는 profile이 있어야 한다. Simulator URL은 userinfo·path·query 없음, 고객 origin은 https, 서버 간 URL은 https, loopback http는 `simulator.allow-loopback-http: true`(로컬 Demo·시험 전용 표식, 운영 설정에 두지 않는다 — 검토 #2 n-4)일 때만.
 * client 키는 환경변수로만 주입한다(저장소에 키 없음 — AN-20). toString은 키를 내보내지 않는다.
 */
@ConfigurationProperties(prefix = "paycore.authentication")
class AuthenticationSettings(
    val backends: Map<String, Backend>,
    val profiles: Map<String, Profile>,
    val simulator: SimulatorConnection,
) : AuthenticationProfiles, CardAuthenticationEndpoints {

    class Backend(val type: String, val profile: String) {
        override fun toString(): String = "Backend(type=$type, profile=$profile)"
    }

    class CardCompany(val code: String, val name: String)

    class PlanPolicy(val version: String, val currency: String, val installmentMonths: List<Int>, val minimumInstallmentAmount: Long?)

    class Profile(val version: String, val cardCompanies: List<CardCompany>, val planPolicy: PlanPolicy)

    class SimulatorConnection(
        val baseUrl: String,
        val customerBaseUrl: String,
        val clientKey: String,
        val connectTimeout: Duration,
        val readTimeout: Duration,
        val allowLoopbackHttp: Boolean = false,
    ) {
        init {
            require(HTTPS_ORIGIN.matches(baseUrl) || (allowLoopbackHttp && LOOPBACK_HTTP.matches(baseUrl))) {
                "simulator.base-url must be https://host[:port] without path (loopback http only with allow-loopback-http — local/test)"
            }
            require(HTTPS_ORIGIN.matches(customerBaseUrl)) { "simulator.customer-base-url must be https://host[:port] without path" }
            require(clientKey.isNotBlank()) { "simulator.client-key must be set" }
            listOf(connectTimeout, readTimeout).forEach { require(!it.isNegative && !it.isZero) { "simulator timeouts must be positive" } }
        }

        override fun toString(): String = "SimulatorConnection(baseUrl=$baseUrl, customerBaseUrl=$customerBaseUrl, clientKey=****)"
    }

    private val resolved: Map<Environment, AuthenticationProfile>

    init {
        resolved = backends.map { (env, backend) ->
            val environment = Environment.entries.firstOrNull { it.name == env } ?: throw IllegalArgumentException("unknown environment for an authentication backend")
            require(backend.type == SIMULATOR) { "authentication backend type ${backend.type} is not available (only SIMULATOR; no REAL adapter)" }
            require(environment == Environment.TEST) { "a SIMULATOR authentication backend is allowed only for TEST payments (LIVE refused)" }
            val profile = profiles[backend.profile] ?: throw IllegalArgumentException("authentication profile ${backend.profile} is not configured")
            environment to profileOf(backend.profile, profile)
        }.toMap()
    }

    override fun profileFor(environment: Environment): AuthenticationProfile? = resolved[environment]

    /** 고객 origin(CSP form-action·인증 시작 redirect 검증 — §12.1·§12.4). 기동 시 https origin 검사를 통과한 값. */
    override val customerOrigin: String get() = simulator.customerBaseUrl

    override fun toString(): String = "AuthenticationSettings(backends=$backends, simulator=$simulator)"

    private fun profileOf(id: String, p: Profile): AuthenticationProfile {
        val currency = CurrencyCode(p.planPolicy.currency)
        return AuthenticationProfile(
            id = id,
            version = p.version,
            cardCompanies = p.cardCompanies.map { CardCompanyOption(it.code, it.name) },
            planPolicy = PaymentPlanPolicy(p.planPolicy.version, currency, p.planPolicy.installmentMonths.toSet(), p.planPolicy.minimumInstallmentAmount?.let { Money(it, currency) }),
        )
    }

    private companion object {
        const val SIMULATOR = "SIMULATOR"
        val HTTPS_ORIGIN = Regex("^https://[a-z0-9.-]+(:[0-9]{1,5})?$")
        val LOOPBACK_HTTP = Regex("^http://(127\\.0\\.0\\.1|localhost)(:[0-9]{1,5})?$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationSettings::class)
class AuthenticationSettingsConfiguration
