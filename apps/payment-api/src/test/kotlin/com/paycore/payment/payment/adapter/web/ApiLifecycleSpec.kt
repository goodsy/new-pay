package com.paycore.payment.payment.adapter.web

import com.paycore.payment.PaymentApiApplication
import com.paycore.payment.PaymentDbAccounts
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.PaymentDbSessionTimeZoneCheck
import com.paycore.payment.payment.application.EligibilityTestConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.health.contributor.Status
import org.springframework.context.ConfigurableApplicationContext
import com.paycore.payment.entitlement.EntitlementEnvelopes
import com.paycore.payment.entitlement.domain.SnapshotFixture
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 프로세스 수준 생명주기(CP-API-001 명세 §9.2·§11.3): 같은 DB에서 애플리케이션 컨텍스트를 **종료·재기동**한 뒤 같은 멱등키 재요청이 저장 결과를
 * 재생한다(메모리 상태 없음 — DB가 유일한 권위 상태). 세션 시간대 검사(L-10)는 기동 단계에서 비UTC 세션·URL 시간대 파라미터를 막는다.
 * 같은 JVM 안의 컨텍스트 재생성이며 프로세스 강제 종료(kill -9)가 아니다(Coordinator 결정 2026-10-14). 컨테이너는 이 spec 전용이다.
 */
class ApiLifecycleSpec : BehaviorSpec({

    val container = PaymentDbAccounts.startedContainer()
    val db = DbAccess(container)

    afterSpec { container.stop() }

    /** 기본 속성(SpringApplicationBuilder.properties = 가장 낮은 우선순위 — application.yml의 `${'$'}{…}` 자리만 채운다). */
    fun properties(url: String = PaymentDbAccounts.urlFor(container, PaymentDbAccounts.PAYMENT_DATABASE)) = mapOf<String, Any>(
        "PAYCORE_PAYMENT_DB_URL" to url,
        "PAYCORE_PAYMENT_DB_USERNAME" to PaymentDbAccounts.PAYMENT_RUNTIME_USER,
        "PAYCORE_PAYMENT_DB_PASSWORD" to PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_RUNTIME_USER),
        "PAYCORE_PAYMENT_DB_MIGRATION_USERNAME" to PaymentDbAccounts.PAYMENT_MIGRATION_USER,
        "PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD" to PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER),
        "server.port" to 0,
    )

    /**
     * [overrides]는 명령행 인자(application.yml보다 우선)로 넘긴다. [testEligibility]가 false면 운영 eligibility 구현(권리 투영)으로 기동한다.
     */
    fun start(
        props: Map<String, Any> = properties(),
        overrides: Map<String, String> = emptyMap(),
        testEligibility: Boolean = true,
    ): ConfigurableApplicationContext {
        val sources = if (testEligibility) arrayOf(PaymentApiApplication::class.java, EligibilityTestConfig::class.java) else arrayOf(PaymentApiApplication::class.java)
        return SpringApplicationBuilder(*sources).properties(props).run(*overrides.map { (k, v) -> "--$k=$v" }.toTypedArray())
    }

    fun failureText(error: Throwable): String = generateSequence(error) { it.cause }.joinToString(" | ") { it.message ?: "" }

    fun portOf(context: ConfigurableApplicationContext): Int = context.environment.getRequiredProperty("local.server.port").toInt()

    Given("a payment committed by one application instance") {
        Then("after that instance stops and a new one starts on the same database, the same key replays the stored result byte for byte") {
            val first = start()
            val cred = TestCredentials.issue(db)
            val key = "restart-key-1"
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = "restart-order-1")
            val created = try {
                first.getBean(PaymentDbSessionTimeZoneCheck::class.java).health().status shouldBe Status.UP
                ApiClient(portOf(first)).post("/v1/payments", body, cred.key, key)
            } finally {
                first.close()
            }
            created.status shouldBe 201

            val second = start()
            try {
                val replay = ApiClient(portOf(second)).post("/v1/payments", body, cred.key, key)
                replay.status shouldBe 200
                replay.header("Idempotent-Replayed") shouldBe "true"
                replay.body shouldBe created.body
            } finally {
                second.close()
            }
            db.count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", cred.mid) shouldBe 1L
        }
    }

    Given("the PAYMENT DB session time zone (CP-DB-001 review2 L-10)") {
        Then("a datasource URL carrying a time-zone parameter is refused at startup") {
            val url = PaymentDbAccounts.urlFor(container, PaymentDbAccounts.PAYMENT_DATABASE) + "&serverTimezone=Asia/Seoul"
            val error = shouldThrow<Exception> { start(properties(url = url)).close() }
            generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" } shouldContain "time-zone parameters"
        }
        Then("a session that is not +00:00 (driver property not forcing the session zone, server zone Asia/Seoul) is refused at startup") {
            val error = shouldThrow<Exception> {
                start(overrides = mapOf("spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession" to "false")).close()
            }
            generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" } shouldContain "session time_zone must be +00:00"
        }
        Then("a driver time conversion other than UTC is refused even when the session itself is +00:00 (data-source properties, not the URL — review round1 m-1)") {
            // 세션은 sessionVariables로 +00:00이지만 driver 변환 시간대(connectionTimeZone)는 Asia/Seoul — 세션 검사만으로는 통과하는 우회다.
            val error = shouldThrow<Exception> {
                start(
                    overrides = mapOf(
                        "spring.datasource.hikari.data-source-properties.connectionTimeZone" to "Asia/Seoul",
                        "spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession" to "false",
                        "spring.datasource.hikari.data-source-properties.sessionVariables" to "time_zone='+00:00'",
                    ),
                ).close()
            }
            failureText(error) shouldContain "JDBC time conversion is not UTC"
        }
        Then("only time-zone related URL parameters are refused") {
            PaymentDbSessionTimeZoneCheck.forbiddenUrlParameters("jdbc:mysql://h/db?useSSL=false&allowPublicKeyRetrieval=true") shouldBe emptyList()
            PaymentDbSessionTimeZoneCheck.forbiddenUrlParameters(
                "jdbc:mysql://h/db?ConnectionTimeZone=UTC&preserveInstants=true&sessionVariables=time_zone='%2B09:00'&serverTimezone=UTC",
            ) shouldBe listOf("connectiontimezone", "servertimezone", "preserveinstants", "sessionvariables")
            // host 부분 속성(address=(…)(key=value))·퍼센트 인코딩된 이름도 찾는다(review round1 m-1 — 보조 검사, 보장은 기동 효과 검사).
            PaymentDbSessionTimeZoneCheck.forbiddenUrlParameters("jdbc:mysql://address=(host=h)(port=3306)(connectionTimeZone=Asia/Seoul)/db") shouldBe
                listOf("connectiontimezone")
            PaymentDbSessionTimeZoneCheck.forbiddenUrlParameters("jdbc:mysql://h/db?connection%54imeZone=Asia/Seoul") shouldBe listOf("connectiontimezone")
            PaymentDbSessionTimeZoneCheck.forbiddenUrlParameters("jdbc:mysql://h/db?sessionVariables=sql_mode='STRICT_ALL_TABLES'") shouldBe emptyList()
            PaymentDbSessionTimeZoneCheck.forbiddenUrlParameters(null) shouldBe emptyList()
        }
    }

    Given("the internal connector settings (review round1 n-4)") {
        Then("an internal port equal to the public server port is refused at startup with a clear message") {
            val port = java.net.ServerSocket(0).use { it.localPort }
            val error = shouldThrow<Exception> {
                start(overrides = mapOf("server.port" to port.toString(), "paycore.payment.api.internal-port" to port.toString())).close()
            }
            failureText(error) shouldContain "internal-port must differ from server.port"
        }
        Then("the optional internal-address binds the internal connector to that address only") {
            val context = start(overrides = mapOf("paycore.payment.api.internal-address" to "127.0.0.1"))
            try {
                val internal = context.getBean(InternalConnector::class.java)
                internal.connector.getProperty("address").toString() shouldContain "127.0.0.1"
                ApiClient(internal.localPort).get("/internal/v1/entitlement-snapshots").status shouldBe 405
            } finally {
                context.close()
            }
        }
    }

    Given("the production eligibility path over HTTP (CP-API-001 G-1)") {
        Then("while an Inbox transaction holds the entitlement head, POST /v1/payments is 503 TEMPORARILY_UNAVAILABLE with Retry-After and stores nothing; afterwards the same key creates") {
            val context = start(
                overrides = mapOf("spring.datasource.hikari.connection-init-sql" to "SET SESSION innodb_lock_wait_timeout = 8"),
                testEligibility = false,
            )
            try {
                val internal = context.getBean(InternalConnector::class.java)
                val ids = SnapshotFixture.ids()
                val leaseStart = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val envelope = EntitlementEnvelopes.body(SnapshotFixture.entitled(ids), SnapshotFixture.leaseAt(leaseStart, duration = Duration.ofMinutes(10)))
                ApiClient(internal.localPort).post(
                    "/internal/v1/entitlement-snapshots", String(envelope, Charsets.UTF_8), null, null,
                    mapOf("Content-Type" to "application/json", "PayCore-Signature" to EntitlementEnvelopes.signature(envelope)),
                ).status shouldBe 200
                val cred = TestCredentials.issue(db, mid = ids.mid)
                val api = ApiClient(portOf(context))
                val body = CreatePaymentRequests.valid(mid = ids.mid, site = ids.siteId, orderId = "g1-order-1")
                db.root().use { holder ->
                    holder.autoCommit = false
                    holder.prepareStatement("SELECT revision FROM entitlement_head WHERE merchant_id = ? FOR UPDATE").use {
                        it.setString(1, ids.merchantId)
                        it.executeQuery().close()
                    }
                    val blocked = CapturedLogs().use { logs ->
                        api.post("/v1/payments", body, cred.key, "g1-key-1").also { r ->
                            // entitlement 저장소 실패의 진단 값도 payment 실패 로그까지 전달된다(review round1 n-6).
                            val line = logs.lines().single { "operation=CreatePayment" in it && "correlationId=${r.header("PayCore-Request-Id")}" in it }
                            line shouldContain "failure=LOCK_TIMEOUT"
                            line shouldContain "vendorCode=1205"
                        }
                    }
                    blocked.status shouldBe 503
                    blocked.header("Retry-After") shouldBe "1"
                    ApiSpecSupport.MAPPER.readTree(blocked.body).get("error").get("code").stringValue() shouldBe "TEMPORARILY_UNAVAILABLE"
                    holder.rollback()
                }
                db.count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ?", ids.mid) shouldBe 0L
                db.count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", ids.mid) shouldBe 0L
                api.post("/v1/payments", body, cred.key, "g1-key-1").status shouldBe 201
                // entitlement 판정 로그도 HTTP 요청의 correlationId를 남긴다(review round2 n-e — 진입 필터 MDC).
                CapturedLogs().use { logs ->
                    val refused = api.post("/v1/payments", CreatePaymentRequests.valid(mid = ids.mid, site = "site_UNKNOWN0001", orderId = "g1-order-2"), cred.key, "g1-key-2")
                    refused.status shouldBe 404
                    val line = logs.lines().single { "entitlement eligibility refused" in it }
                    line shouldContain "correlationId=${refused.header("PayCore-Request-Id")}"
                    line shouldContain "result=NOT_FOUND_OR_FORBIDDEN"
                }
                // 요청이 끝나면 MDC가 비워진다(스레드 재사용 시 다른 요청 ID가 남지 않는다).
                org.slf4j.MDC.get("correlationId") shouldBe null
            } finally {
                context.close()
            }
        }
    }
})

