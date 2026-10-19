package com.paycore.payment.payment.adapter.web

import com.paycore.payment.PaymentMySqlContainerConfig
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjectionConfig
import com.paycore.payment.payment.adapter.persistence.PAYMENT_DB_TEST_FLYWAY_LOCATIONS
import com.paycore.payment.payment.adapter.persistence.PaymentDbTestConfig
import com.paycore.payment.payment.application.EligibilityTestConfig
import com.paycore.payment.payment.application.CardAuthenticationTestConfig
import com.paycore.payment.payment.application.SecureRandomPaymentIdentifierGenerator
import com.paycore.payment.payment.application.SiteWebHostTestConfig
import com.paycore.payment.payment.application.provided.NewPaymentIdentifiers
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentIdentifierGenerator
import com.paycore.payment.payment.domain.Environment
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * CP-API-001 HTTP 통합 시험 구성: **실제 내장 Tomcat**(공개 포트 + 내부 connector), 실제 보안 필터 체인, 실제 MySQL 8.4.11(runtime 계정·표 단위
 * 권한), 운영 Credential 검증 경로(PAYMENT 투영 표 — 시험 행은 [TestCredentials]가 root 연결로 넣는다). eligibility는 시험 대역
 * ([EligibilityTestConfig] — 권리 투영 준비 없이 응답을 정함), 시계·식별자 생성기는 시험 제어 가능한 대역([ApiTestConfig]).
 * 같은 annotation의 spec들은 컨텍스트 하나를 공유한다. lock wait 8초·pool 16은 시험 전용 값이다(PaymentDbTest와 같은 이유).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(
    PaymentMySqlContainerConfig::class, PaymentDbTestConfig::class, FaultInjectionConfig::class, EligibilityTestConfig::class, ApiTestConfig::class,
    SiteWebHostTestConfig::class, CardAuthenticationTestConfig::class, com.paycore.payment.payment.application.AdmissionTestConfig::class,
    com.paycore.payment.payment.application.ProgrammableVanTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 8",
        "spring.datasource.hikari.maximum-pool-size=16",
        PAYMENT_DB_TEST_FLYWAY_LOCATIONS,
    ],
)
annotation class PaymentApiTest

/** 시험 시계(@Primary). 기본은 [default] 고정 시각, 시험이 옮긴 뒤 [reset]한다. */
class MutablePaymentClock(private val default: Instant) : PaymentClock {
    private val instant = AtomicReference(default)

    override fun now(): Instant = instant.get()

    fun set(at: Instant) = instant.set(at)

    fun reset() = instant.set(default)
}

/** 시험 식별자 생성기(@Primary): 기본은 운영 생성기, [enqueue]한 값이 있으면 그것을 먼저 낸다(서버 생성 ID 충돌 주입). */
class ScriptedIdentifierGenerator(private val real: PaymentIdentifierGenerator) : PaymentIdentifierGenerator {
    private val scripted = ConcurrentLinkedQueue<NewPaymentIdentifiers>()

    override fun newIdentifiers(): NewPaymentIdentifiers = scripted.poll() ?: real.newIdentifiers()

    fun enqueue(vararg ids: NewPaymentIdentifiers) = scripted.addAll(ids)

    fun real(): NewPaymentIdentifiers = real.newIdentifiers()

    fun reset() = scripted.clear()
}

@TestConfiguration(proxyBeanMethods = false)
class ApiTestConfig {
    @Bean
    @Primary
    fun mutablePaymentClock(): MutablePaymentClock = MutablePaymentClock(ApiTestTimes.T0)

    @Bean
    @Primary
    fun scriptedIdentifierGenerator(clock: MutablePaymentClock): ScriptedIdentifierGenerator =
        ScriptedIdentifierGenerator(SecureRandomPaymentIdentifierGenerator(clock))
}

object ApiTestTimes {
    /** API 시험 기본 서버 시각(ms, UTC). */
    val T0: Instant = Instant.parse("2026-10-14T01:02:03.456Z")
}

/**
 * 시험 전용 Credential 투영 행(D-CPA-001 형식의 **시험 키** — 저장소에 운영 키 없음). 운영 main 코드에는 투영 쓰기 경로가 없으므로(MASTER-CREDENTIAL-001)
 * root 연결로 넣는다(시험 투영 seeding 경로). 키 원문은 시험 실행마다 [SecureRandom]으로 만든다.
 */
object TestCredentials {
    private val random = SecureRandom()

    data class Issued(val key: String, val keyId: String, val mid: String, val environment: Environment)

    fun issue(
        db: DbAccess,
        mid: String = "mid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26).uppercase(),
        environment: Environment = Environment.LIVE,
        status: String = "ACTIVE",
        validFrom: Instant = Instant.parse("2000-01-01T00:00:00Z"),
        validUntil: Instant? = null,
        leaseUntil: Instant = Instant.parse("2100-01-01T00:00:00Z"),
    ): Issued {
        val keyId = "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val key = "pc_${environment.name.lowercase()}_sk_$keyId.$secret"
        db.withRoot { c ->
            c.prepareStatement(
                "INSERT INTO merchant_api_credential (key_id, merchant_account_id, environment, secret_sha256, status, valid_from, valid_until," +
                    " lease_until, revision, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
            ).use { ps ->
                ps.setString(1, keyId)
                ps.setString(2, mid)
                ps.setString(3, environment.name)
                ps.setString(4, sha256(key))
                ps.setString(5, status)
                ps.setObject(6, utc(validFrom))
                ps.setObject(7, validUntil?.let(::utc))
                ps.setObject(8, utc(leaseUntil))
                ps.setObject(9, utc(validFrom))
                ps.executeUpdate()
            }
        }
        return Issued(key, keyId, mid, environment)
    }

    /** 투영 행 상태 변경(폐기 전파를 흉내 낸다 — 운영 경로는 MASTER-CREDENTIAL-001). */
    fun revoke(db: DbAccess, issued: Issued) {
        db.withRoot { c -> c.prepareStatement("UPDATE merchant_api_credential SET status = 'REVOKED' WHERE key_id = ?").use { it.setString(1, issued.keyId); it.executeUpdate() } }
    }

    fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    /** DATETIME(3) UTC 벽시계 값(관례 §9.1 — root 연결은 Hikari driver 속성이 없으므로 LocalDateTime UTC로 넣는다). */
    private fun utc(at: Instant): LocalDateTime = LocalDateTime.ofInstant(at, ZoneOffset.UTC)
}

/** 실제 HTTP 클라이언트(JDK). 응답 본문은 UTF-8 문자열. */
class ApiClient(private val port: Int) {
    data class Response(val status: Int, val headers: Map<String, List<String>>, val body: String) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun post(
        path: String,
        body: String,
        key: String? = null,
        idempotencyKey: String? = null,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    ): Response {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8)))
        headers.forEach { (k, v) -> builder.header(k, v) }
        key?.let { builder.header("Authorization", "Bearer $it") }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return send(builder.build())
    }

    fun get(path: String, key: String? = null): Response {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(30)).GET()
        key?.let { builder.header("Authorization", "Bearer $it") }
        return send(builder.build())
    }

    private fun send(request: HttpRequest): Response {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return Response(response.statusCode(), response.headers().map(), response.body())
    }
}
