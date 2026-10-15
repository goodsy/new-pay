package com.paycore.payment.payment.adapter.web

import com.paycore.payment.entitlement.EntitlementEnvelopes
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlStore
import com.paycore.payment.payment.adapter.persistence.JpaPaymentStore
import com.paycore.payment.payment.domain.Environment
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import java.time.Instant
import javax.sql.DataSource
import java.util.UUID

/**
 * 가맹점 인증·권한·경로 격리(D-CPA-001, 계약 §2·§9, CP-API-001 명세 §5·§8). 실서버 두 connector(공개·내부)와 실제 보안 필터 체인으로 본다.
 */
@PaymentApiTest
class ApiSecuritySpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val internal: InternalConnector,
    private val registry: MeterRegistry,
    private val faults: FaultInjector,
    private val dataSource: DataSource,
    payments: JpaPaymentStore,
    controls: JpaOrderPaymentControlStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val publicPort = env.getRequiredProperty("local.server.port").toInt()
    val api = ApiClient(publicPort)
    val s = ApiSpecSupport(db, payments, controls, transactionManager)

    afterEach { faults.reset() }

    /** 현재 TX에 묶인 PAYMENT 연결을 root로 끊는다(실제 연결 끊김 — 운영과 같은 JDBC·TX 경로를 거친다). */
    fun killCurrentConnection() {
        val connection = DataSourceUtils.getConnection(dataSource)
        val id = connection.createStatement().use { st -> st.executeQuery("SELECT CONNECTION_ID()").use { rs -> rs.next(); rs.getLong(1) } }
        DataSourceUtils.releaseConnection(connection, dataSource)
        db.withRoot { c -> c.createStatement().use { it.execute("KILL CONNECTION $id") } }
    }

    fun authFailures(reason: String): Double = registry.find(UnauthenticatedEntryPoint.AUTH_FAILURES).tag("reason", reason).counter()?.count() ?: 0.0

    Given("requests that are not authenticated (D-CPA-001: every cause looks the same)") {
        Then("missing, malformed, unknown, wrong secret, wrong environment prefix, revoked, not yet valid, expired and lease-expired keys are all 401") {
            val ok = TestCredentials.issue(db)
            val revoked = TestCredentials.issue(db, status = "REVOKED")
            val future = TestCredentials.issue(db, validFrom = Instant.parse("2099-01-01T00:00:00Z"))
            val ended = TestCredentials.issue(db, validFrom = Instant.parse("2000-01-01T00:00:00Z"), validUntil = Instant.parse("2001-01-01T00:00:00Z"))
            val stale = TestCredentials.issue(db, leaseUntil = Instant.parse("2026-10-14T01:02:03.456Z"))
            val before = authFailures("REVOKED")
            val keys: List<String?> = listOf(
                null,
                "not-a-key",
                ok.key.replace(ok.keyId, "unknownKey00000001"),
                ok.key.dropLast(1) + (if (ok.key.last() == 'A') 'B' else 'A'),
                ok.key.replace("pc_live_sk_", "pc_test_sk_"),
                revoked.key, future.key, ended.key, stale.key,
            )
            val responses = keys.map { api.post("/v1/payments", CreatePaymentRequests.valid(mid = ok.mid), it, s.newKey()) }
            responses.map { it.status }.toSet() shouldBe setOf(401)
            responses.map { it.body.replace(Regex("\"requestId\":\"[^\"]+\""), "") }.toSet().size shouldBe 1
            responses.forEach {
                it.header("WWW-Authenticate") shouldBe "Bearer realm=\"paycore\""
                it.header("PayCore-Request-Id") shouldBe s.json(it.body).get("error").get("requestId").stringValue()
                s.json(it.body).get("error").get("code").stringValue() shouldBe "UNAUTHENTICATED"
            }
            authFailures("REVOKED") shouldBe before + 1
            s.rows(ok.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
        Then("the Authorization scheme must be Bearer and appear once") {
            val ok = TestCredentials.issue(db)
            api.post("/v1/payments", CreatePaymentRequests.valid(mid = ok.mid), null, s.newKey(),
                mapOf("Content-Type" to "application/json", "Authorization" to "Basic ${ok.key}")).status shouldBe 401
            api.post("/v1/payments", CreatePaymentRequests.valid(mid = ok.mid), ok.key, s.newKey(),
                mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${ok.key}")).status shouldBe 401
        }
        Then("the scheme name is case-insensitive (RFC 7235 §2.1, review round1 n-2) but the key itself is compared exactly") {
            val ok = TestCredentials.issue(db)
            listOf("bearer", "BEARER", "Bearer").forEach { scheme ->
                api.post("/v1/payments", CreatePaymentRequests.valid(mid = ok.mid, orderId = s.newOrderId()), null, s.newKey(),
                    mapOf("Content-Type" to "application/json", "Authorization" to "$scheme ${ok.key}")).status shouldBe 201
            }
            val secretUpper = ok.key.substringBefore('.') + "." + ok.key.substringAfter('.').uppercase()
            if (secretUpper != ok.key) {
                api.post("/v1/payments", CreatePaymentRequests.valid(mid = ok.mid, orderId = s.newOrderId()), secretUpper, s.newKey()).status shouldBe 401
            }
        }
    }

    Given("the credential read loses its database connection (review round2 m-1)") {
        listOf(
            FaultPoint.CREDENTIAL_BEFORE_FIND to "during the read (the query and the rollback fail)",
            FaultPoint.CREDENTIAL_FIND to "after the read (the commit of the read transaction fails)",
        ).forEach { (point, moment) ->
            Then("a connection killed $moment is 503 TEMPORARILY_UNAVAILABLE with Retry-After, never 500 or a false 401; the log has the classification only") {
                val cred = TestCredentials.issue(db)
                val armed = java.util.concurrent.atomic.AtomicBoolean(true)
                faults.after(point) { if (armed.compareAndSet(true, false)) killCurrentConnection() }
                CapturedLogs().use { logs ->
                    val r = api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), cred.key, s.newKey())
                    r.status shouldBe 503
                    s.json(r.body).get("error").get("code").stringValue() shouldBe "TEMPORARILY_UNAVAILABLE"
                    s.json(r.body).get("error").get("retryable").booleanValue() shouldBe true
                    r.header("Retry-After") shouldBe "1"
                    r.header("WWW-Authenticate") shouldBe null
                    val line = logs.lines().single { "operation=AuthenticateMerchant" in it && "correlationId=${r.header("PayCore-Request-Id")}" in it }
                    line shouldContain "result=TEMPORARILY_UNAVAILABLE"
                    line shouldContain "failure="
                    line shouldNotContain "failure=null"
                    line shouldNotContain "Communications link failure"
                    line shouldNotContain "|"
                    logs.lines().none { "result=INTERNAL_ERROR" in it } shouldBe true
                }
                armed.get() shouldBe false
                s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
                // 끊긴 연결은 풀에서 빠지고 다음 요청은 정상이다.
                api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), cred.key, s.newKey()).status shouldBe 201
            }
        }
    }

    Given("identifiers carrying line breaks (review round2 m-2: no log line injection)") {
        Then("a merchantSiteId or merchantAccountId with a newline is 400 INVALID_REQUEST, nothing is stored, and no log line starts with the forged text") {
            val cred = TestCredentials.issue(db)
            val marker = "FORGED-" + s.newKey()
            CapturedLogs().use { logs ->
                listOf("merchantSiteId", "merchantAccountId").forEach { field ->
                    val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
                        .replace(Regex("\"$field\":\"[^\"]*\"")) { "\"$field\":\"x\\nINFO $marker\"" }
                    val r = api.post("/v1/payments", body, cred.key, s.newKey())
                    r.status shouldBe 400
                    s.json(r.body).get("error").get("code").stringValue() shouldBe "INVALID_REQUEST"
                    s.json(r.body).get("error").get("field").stringValue() shouldBe field
                    r.body shouldNotContain marker
                }
                logs.lines().forEach { it shouldNotContain marker }
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
    }

    Given("MID and environment authority (contract §2, AC 1)") {
        Then("a body merchantAccountId other than the authenticated MID is 403 MERCHANT_SCOPE_MISMATCH and reveals nothing about that MID") {
            val a = TestCredentials.issue(db)
            val b = TestCredentials.issue(db)
            api.post("/v1/payments", CreatePaymentRequests.valid(mid = b.mid, orderId = s.newOrderId()), b.key, s.newKey()).status shouldBe 201
            val r = api.post("/v1/payments", CreatePaymentRequests.valid(mid = b.mid, orderId = s.newOrderId()), a.key, s.newKey())
            r.status shouldBe 403
            s.json(r.body).get("error").get("code").stringValue() shouldBe "MERCHANT_SCOPE_MISMATCH"
            r.body shouldNotContain b.mid
            s.rows(a.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
    }

    Given("a stored result and a later request with the same key (D-CP-003: authority is re-checked every time)") {
        Then("after the credential is revoked the replay is 401 and returns no stored result") {
            val cred = TestCredentials.issue(db)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId())
            val first = api.post("/v1/payments", body, cred.key, key)
            first.status shouldBe 201
            TestCredentials.revoke(db, cred)
            val replay = api.post("/v1/payments", body, cred.key, key)
            replay.status shouldBe 401
            replay.body shouldNotContain s.json(first.body).get("paymentId").stringValue()
        }
        Then("another MID's credential with the original body is 403 before the idempotency lookup; the same MID's TEST key is another scope") {
            val live = TestCredentials.issue(db)
            val other = TestCredentials.issue(db)
            val test = TestCredentials.issue(db, mid = live.mid, environment = Environment.TEST)
            val key = s.newKey()
            val body = CreatePaymentRequests.valid(mid = live.mid, orderId = s.newOrderId())
            val first = api.post("/v1/payments", body, live.key, key)
            val foreign = api.post("/v1/payments", body, other.key, key)
            foreign.status shouldBe 403
            foreign.body shouldNotContain s.json(first.body).get("paymentId").stringValue()
            val testScope = api.post("/v1/payments", body, test.key, key)
            testScope.status shouldBe 201
            testScope.header("Idempotent-Replayed") shouldBe "false"
            api.post("/v1/payments", body, live.key, key).header("Idempotent-Replayed") shouldBe "true"
        }
    }

    Given("routing outside the payment API") {
        Then("unknown paths are 401 without authentication and 404 with it; other methods are 405; health is public without details") {
            val cred = TestCredentials.issue(db)
            api.get("/v1/nothing").status shouldBe 401
            val notFound = api.get("/v1/nothing", cred.key)
            notFound.status shouldBe 404
            s.json(notFound.body).get("error").get("code").stringValue() shouldBe "NOT_FOUND"
            api.get("/v1/payments", cred.key).status shouldBe 405
            val health = api.get("/actuator/health")
            health.status shouldBe 200
            // 세부(components·details — DB·세션 시간대 등)는 없다. 상태와 probe group 이름만.
            (s.json(health.body).properties().map { it.key }.toSet() - setOf("status", "groups")) shouldBe emptySet()
        }
        Then("container error dispatch and firewall rejections use the public API error format without echoing the path (review round1 n-3)") {
            val cred = TestCredentials.issue(db)
            fun shape(r: ApiClient.Response, status: Int, code: String) {
                r.status shouldBe status
                r.header("Content-Type")!!.startsWith("application/json") shouldBe true
                val error = s.json(r.body).get("error")
                error.get("code").stringValue() shouldBe code
                error.get("requestId").stringValue() shouldBe r.header("PayCore-Request-Id")
                s.json(r.body).properties().map { it.key }.toSet() shouldBe setOf("error")
                r.body shouldNotContain "timestamp"
                r.body shouldNotContain "\"path\""
            }
            // 405는 MVC가 sendError → /error dispatch → ApiErrorController.
            shape(api.get("/v1/payments", cred.key), 405, "METHOD_NOT_ALLOWED")
            // /error 직접 호출: 인증 없으면 401, 인증되면 없는 자원(404).
            shape(api.get("/error"), 401, "UNAUTHENTICATED")
            shape(api.get("/error", cred.key), 404, "NOT_FOUND")
            // StrictHttpFirewall 거절(`;` 경로 매개변수, 인코딩된 `%`) → 400 INVALID_REQUEST, 경로 원문 없음.
            listOf("/v1/payments;leakparam=1", "/v1/payments%25leakparam").forEach { path ->
                val r = api.post(path, CreatePaymentRequests.valid(mid = cred.mid), cred.key, s.newKey())
                shape(r, 400, "INVALID_REQUEST")
                r.body shouldNotContain "leakparam"
            }
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
    }

    Given("/internal path isolation (MASTER-SNAPSHOT-001 carry-over, spec §8)") {
        Then("the public port answers 404 for /internal (even with a valid signature) and the internal port answers 404 for /v1") {
            val cred = TestCredentials.issue(db)
            internal.localPort shouldBe internal.localPort.coerceAtLeast(1)
            (internal.localPort != publicPort) shouldBe true
            // 서명은 올바르지만 내용이 빈 봉투(내부 포트에서는 계약 검증 단계까지 간다).
            val envelopeBytes = EntitlementEnvelopes.envelope(UUID.randomUUID().toString(), 1, "mer_isolation", 1, "{}")
            val envelope = String(envelopeBytes, Charsets.UTF_8) to EntitlementEnvelopes.signature(envelopeBytes)
            val viaPublic = api.post("/internal/v1/entitlement-snapshots", envelope.first, null, null,
                mapOf("Content-Type" to "application/json", "PayCore-Signature" to envelope.second))
            viaPublic.status shouldBe 404
            s.json(viaPublic.body).get("error").get("code").stringValue() shouldBe "NOT_FOUND"
            // 경로 표기를 바꿔도(정규화 전 `..`·`;`) 판단은 컨테이너가 정규화한 경로로 한다.
            val disguised = api.post("/v1/..;/internal/v1/entitlement-snapshots", envelope.first, null, null,
                mapOf("Content-Type" to "application/json", "PayCore-Signature" to envelope.second)).status
            (disguised == 400 || disguised == 404) shouldBe true

            val internalApi = ApiClient(internal.localPort)
            internalApi.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid), cred.key, s.newKey()).status shouldBe 404
            internalApi.get("/actuator/health").status shouldBe 404
            // 내부 포트의 Inbox는 HMAC으로만 판단한다(가맹점 Credential 불필요·무관) — 봉투 내용 검증까지 도달한다(400/422, 404 아님).
            val viaInternal = internalApi.post("/internal/v1/entitlement-snapshots", envelope.first, null, null,
                mapOf("Content-Type" to "application/json", "PayCore-Signature" to envelope.second))
            (viaInternal.status == 400 || viaInternal.status == 422) shouldBe true
            s.rows(cred.mid) shouldBe s.rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
        }
    }

    Given("the credential projection is read-only for the application (V6)") {
        Then("the runtime account cannot insert, update or delete credential rows (1142)") {
            db.withApp { c ->
                with(db) {
                    listOf(
                        c.errorCodeOf(
                            "INSERT INTO merchant_api_credential (key_id, merchant_account_id, environment, secret_sha256, status, valid_from, lease_until," +
                                " revision, updated_at) VALUES ('privtest0001', 'mid_x', 'LIVE', REPEAT('a', 64), 'ACTIVE', '2000-01-01', '2100-01-01', 1," +
                                " '2000-01-01')",
                        ),
                        c.errorCodeOf("UPDATE merchant_api_credential SET status = 'ACTIVE' WHERE key_id = 'none'"),
                        c.errorCodeOf("DELETE FROM merchant_api_credential WHERE key_id = 'none'"),
                    )
                }
            } shouldBe listOf(1142, 1142, 1142)
            db.count("SELECT COUNT(*) FROM merchant_api_credential WHERE key_id = ?", "privtest0001") shouldBe 0L
        }
    }
})
