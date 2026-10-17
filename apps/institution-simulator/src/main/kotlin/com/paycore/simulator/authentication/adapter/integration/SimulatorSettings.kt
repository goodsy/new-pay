package com.paycore.simulator.authentication.adapter.integration

import com.paycore.simulator.authentication.application.required.SimulatorClient
import com.paycore.simulator.authentication.application.required.SimulatorClients
import com.paycore.simulator.authentication.application.required.SimulatorTimings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * Simulator 설정(`paycore.simulator`, AUTHN-001 §13 — **Demo, 운영값 아님, 운영 기본값 없음**: 값이 없거나 틀리면 기동 실패). PG client는 Bearer 키의 SHA-256
 * digest만 보관한다(AN-20 — 저장소에 키 원문 없음, 로컬·시험 키는 환경변수/시험 자원). origin은 `https://host[:port]`(path·userinfo·query 없음).
 */
@ConfigurationProperties(prefix = "paycore.simulator")
class SimulatorSettings(
    override val customerBaseUrl: String,
    override val transactionTtl: Duration,
    override val resultValidity: Duration,
    override val lookupDelay: Duration,
    val clients: List<Client>,
) : SimulatorTimings, SimulatorClients {

    class Client(val id: String, val keySha256: String, val returnOrigin: String) {
        init {
            require(ID.matches(id)) { "client id must be 1..64 of [a-z0-9-]" }
            require(SHA256.matches(keySha256)) { "client key digest must be 64 lower-case hex characters" }
            require(ORIGIN.matches(returnOrigin)) { "client return origin must be https://host[:port] without path" }
        }

        override fun toString(): String = "Client(id=$id, returnOrigin=$returnOrigin)"
    }

    init {
        require(ORIGIN.matches(customerBaseUrl)) { "customer-base-url must be https://host[:port] without path" }
        listOf(transactionTtl, resultValidity).forEach { require(!it.isNegative && !it.isZero) { "durations must be positive" } }
        require(!lookupDelay.isNegative) { "lookup-delay must not be negative" }
        require(clients.isNotEmpty() && clients.map { it.id }.toSet().size == clients.size) { "clients must be non-empty with unique ids" }
    }

    override fun authenticate(bearerKey: String): SimulatorClient? {
        if (bearerKey.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bearerKey.toByteArray(Charsets.UTF_8))
        return clients.firstOrNull { MessageDigest.isEqual(HexFormat.of().parseHex(it.keySha256), digest) }?.let { SimulatorClient(it.id, it.returnOrigin) }
    }

    override fun find(clientId: String): SimulatorClient? = clients.firstOrNull { it.id == clientId }?.let { SimulatorClient(it.id, it.returnOrigin) }

    override fun toString(): String = "SimulatorSettings(customerBaseUrl=$customerBaseUrl, clients=$clients)"

    private companion object {
        val ID = Regex("^[a-z0-9-]{1,64}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val ORIGIN = Regex("^https://[a-z0-9.-]+(:[0-9]{1,5})?$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulatorSettings::class)
class SimulatorSettingsConfiguration
