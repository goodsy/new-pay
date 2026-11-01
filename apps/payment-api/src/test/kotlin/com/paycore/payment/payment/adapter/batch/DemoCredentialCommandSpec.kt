package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.PaymentDbAccounts
import com.paycore.payment.PaymentMySqlContainerConfig
import com.paycore.payment.entitlement.EntitlementEnvelopes
import com.paycore.payment.entitlement.application.provided.InboundSnapshotEnvelope
import com.paycore.payment.entitlement.application.provided.ReceiveEntitlementSnapshotUseCase
import com.paycore.payment.entitlement.application.provided.ReceiveResult
import com.paycore.payment.entitlement.domain.EntitlementContent
import com.paycore.payment.entitlement.domain.InboxOutcome
import com.paycore.payment.entitlement.domain.Lease
import com.paycore.payment.entitlement.domain.PolicyContentStatus
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapTerms
import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.application.DemoCredentialIssueService
import com.paycore.payment.payment.application.provided.AuthenticationFailureReason
import com.paycore.payment.payment.application.provided.DemoCredentialIssueCommand
import com.paycore.payment.payment.application.provided.DemoCredentialIssueResult
import com.paycore.payment.payment.application.provided.DemoCredentialIssueUseCase
import com.paycore.payment.payment.application.provided.MerchantAuthenticationResult
import com.paycore.payment.payment.application.provided.MerchantAuthenticator
import com.paycore.payment.runCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.testcontainers.mysql.MySQLContainer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `demo-credential` 명령(DEMO-SEED-001 명세 §4.5~§4.8 — I-3·I-15·I-16·I-17·I-18·I-21·I-24·I-25)을 실제 MySQL 8.4.11·runtime 계정·**비웹 명령 컨텍스트**
 * (`paycore.payment.command=demo-credential`, DEMO, 주기 job 다섯 개를 켠 설정)에서 확인한다. 발급 행은 DB당 하나(고정 id)라 시험 순서가 의미를 갖고,
 * 시나리오 사이 초기화는 root 연결로만 한다(runtime 계정은 DELETE가 없다 — I-24). 기대값은 구현 상수와 독립 리터럴(Test Fixture Policy).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "paycore.payment.command=demo-credential",
        "paycore.payment.deployment-mode=DEMO",
        "paycore.authorization.timing.recovery.enabled=true",
        "paycore.cancellation.timing.recovery.enabled=true",
        "paycore.webhook.fanout.enabled=true",
        "paycore.webhook.delivery.job.enabled=true",
        "paycore.payment.checkout-expiry.job.enabled=true",
    ],
)
@Import(PaymentMySqlContainerConfig::class, DemoCredentialTestConfig::class)
class DemoCredentialCommandSpec(
    private val context: ApplicationContext,
    private val commandLine: DemoCredentialCommandLine,
    private val issuer: DemoCredentialIssueUseCase,
    private val inbox: ReceiveEntitlementSnapshotUseCase,
    private val authenticator: MerchantAuthenticator,
    private val keys: ScriptedDemoKeySource,
    private val clock: MutablePaymentClock,
    private val container: MySQLContainer,
    private val issuanceStore: com.paycore.payment.payment.application.required.DemoCredentialIssuanceStore,
    private val projectionWriter: com.paycore.payment.payment.application.required.DemoCredentialProjectionWriter,
    private val transactionManager: org.springframework.transaction.PlatformTransactionManager,
) : FunSpec({

    val db = PaymentDbAccounts.PAYMENT_DATABASE

    afterAny {
        keys.reset()
        clock.reset()
    }

    fun root(): Connection = PaymentDbAccounts.root(container)

    fun runtime(): Connection = PaymentDbAccounts.connect(container, db, PaymentDbAccounts.PAYMENT_RUNTIME_USER)

    fun count(sql: String, vararg args: Any?): Long = root().use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun issuances() = count("SELECT COUNT(*) FROM $db.demo_credential_issuance")
    fun credentialsOf(mid: String) = count("SELECT COUNT(*) FROM $db.merchant_api_credential WHERE merchant_account_id = ?", mid)

    fun privateDir(): Path = Files.createTempDirectory("demo-credential-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

    fun dataLines(file: Path): List<Pair<String, String>> =
        Files.readAllLines(file).filter { !it.startsWith("#") }.map { it.substringBefore('\t') to it.substringAfter('\t') }

    fun sha256Hex(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    fun apply(content: EntitlementContent) {
        val now = Instant.now()
        val lease = Lease(now, 0, now.plus(Duration.ofHours(1)))
        val eventId = UUID.randomUUID().toString()
        val body = EntitlementEnvelopes.body(content, lease, eventId)
        val result = inbox.receive(
            InboundSnapshotEnvelope("EntitlementSnapshot", SnapshotFixture.incoming(content, lease, eventId), String(body, StandardCharsets.UTF_8), sha256Hex(String(body, StandardCharsets.UTF_8))),
        )
        result.shouldBeInstanceOf<ReceiveResult.Stored>().outcome shouldBe InboxOutcome.APPLIED
    }

    fun run(out: Path, mid: String): Int = commandLine.run("demo-credential", mapOf("output" to "file:$out", "merchant-account-id" to mid))

    val demoIds = SnapshotFixture.ids()
    val demoMid = demoIds.mid

    test("I-3: the command context has no periodic job bean and no scheduled task although every job is switched on; the start-up run had no MID (USAGE)") {
        listOf(
            "AuthorizationRecoveryScheduler", "CancellationRecoveryScheduler", "MerchantNotificationFanoutScheduler", "MerchantWebhookDeliveryScheduler",
            "RequestedPaymentExpiryScheduler",
        ).forEach { name -> context.beanDefinitionNames.filter { context.getType(it)?.simpleName == name }.shouldBeEmpty() }
        context.getBeansOfType(ScheduledTaskHolder::class.java).values.flatMap { it.scheduledTasks }.shouldBeEmpty()
        context.getBean(DemoCredentialCommandRunner::class.java).exitCode shouldBe 2
    }

    test("the non-web command context starts without HttpSecurity and has no SecurityFilterChain (web security is configured only in a servlet context)") {
        (context is org.springframework.web.context.WebApplicationContext) shouldBe false
        context.getBeanNamesForType(org.springframework.security.config.annotation.web.builders.HttpSecurity::class.java).toList().shouldBeEmpty()
        context.getBeanNamesForType(org.springframework.security.web.SecurityFilterChain::class.java).toList().shouldBeEmpty()
        listOf("PaymentApiSecurityConfiguration", "CheckoutSecurityConfiguration", "AdminChannelSecurityConfiguration").forEach { name ->
            context.beanDefinitionNames.filter { context.getType(it)?.simpleName?.startsWith(name) == true }.shouldBeEmpty()
        }
    }

    test("argument and output refusals happen before any DB change (USAGE 2, OUTPUT_REFUSED 6 — I-15)") {
        val dir = privateDir()
        val existing = Files.createFile(dir.resolve("exists.tsv"))
        val shared = Files.createTempDirectory("demo-credential-shared-").also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxrwx")) }
        commandLine.run("demo-credentials", mapOf("output" to "file:${dir.resolve("a.tsv")}", "merchant-account-id" to demoMid)) shouldBe 2
        commandLine.run("demo-credential", mapOf("output" to "file:${dir.resolve("b.tsv")}", "merchant-account-id" to "mid-bad")) shouldBe 2
        commandLine.run("demo-credential", mapOf("output" to "file:${dir.resolve("c.tsv")}")) shouldBe 2
        commandLine.run("demo-credential", mapOf("output" to "elsewhere", "merchant-account-id" to demoMid)) shouldBe 2
        run(existing, demoMid) shouldBe 6
        run(shared.resolve("d.tsv"), demoMid) shouldBe 6
        commandLine.run("demo-credential", mapOf("output" to "tty", "merchant-account-id" to demoMid)) shouldBe 6
        listOf("a.tsv", "b.tsv", "c.tsv").forEach { Files.exists(dir.resolve(it)) shouldBe false }
        Files.exists(shared.resolve("d.tsv")) shouldBe false
        issuances() shouldBe 0
    }

    test("I-16·I-21: a MID without a PAYMENT snapshot → CONDITION_REFUSED (4) MID_UNKNOWN, nothing written, prepared file removed") {
        val dir = privateDir()
        run(dir.resolve("k.tsv"), demoMid) shouldBe 4
        issuer.issue(DemoCredentialIssueCommand(demoMid, "demo-credential-t1")) shouldBe
            DemoCredentialIssueResult.Refused(com.paycore.payment.payment.application.provided.DemoCredentialRefusal.MID_UNKNOWN)
        Files.exists(dir.resolve("k.tsv")) shouldBe false
        issuances() shouldBe 0
        credentialsOf(demoMid) shouldBe 0
    }

    test("I-21 (m5): a MID whose policy assignments are not all DEMO, or has none, → 4 NOT_DEMO_MERCHANT, nothing written") {
        val undefinedIds = SnapshotFixture.ids()
        apply(
            SnapshotFixture.entitledWithTerms(undefinedIds).mapTerms { t ->
                t.copy(policyAssignments = t.policyAssignments.mapIndexed { i, a -> if (i == 0) a.copy(contentStatus = PolicyContentStatus.UNDEFINED) else a })
            },
        )
        val noTermsIds = SnapshotFixture.ids()
        apply(SnapshotFixture.entitled(noTermsIds))
        val dir = privateDir()
        run(dir.resolve("u.tsv"), undefinedIds.mid) shouldBe 4
        run(dir.resolve("v.tsv"), noTermsIds.mid) shouldBe 4
        issuer.issue(DemoCredentialIssueCommand(undefinedIds.mid, "demo-credential-t2")) shouldBe
            DemoCredentialIssueResult.Refused(com.paycore.payment.payment.application.provided.DemoCredentialRefusal.NOT_DEMO_MERCHANT)
        issuances() shouldBe 0
        credentialsOf(undefinedIds.mid) shouldBe 0
        credentialsOf(noTermsIds.mid) shouldBe 0
    }

    test("I-25: a keyId already present in the projection → KEY_ID_COLLISION (11), no issuance row (not LOST), file removed; the rerun with a new keyId succeeds") {
        apply(SnapshotFixture.entitledWithTerms(demoIds))
        val taken = "demoTAKEN00000000001"
        root().use { c ->
            c.prepareStatement(
                "INSERT INTO $db.merchant_api_credential (key_id, merchant_account_id, environment, secret_sha256, status, valid_from, valid_until, lease_until, revision, updated_at) " +
                    "VALUES (?, 'mid_OTHER', 'TEST', ?, 'ACTIVE', ?, NULL, ?, 1, ?)",
            ).use { ps ->
                val at = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"))
                ps.setString(1, taken); ps.setString(2, "0".repeat(64)); ps.setTimestamp(3, at); ps.setTimestamp(4, at); ps.setTimestamp(5, at)
                ps.executeUpdate()
            }
        }
        keys.force(DemoCredentialIssueService.KeyMaterial(taken, "A".repeat(43)))
        val dir = privateDir()
        run(dir.resolve("collide.tsv"), demoMid) shouldBe 11
        Files.exists(dir.resolve("collide.tsv")) shouldBe false
        issuances() shouldBe 0
        credentialsOf(demoMid) shouldBe 0
    }

    lateinit var issuedFile: Path
    val logs = mutableListOf<String>()
    val stdout = java.io.ByteArrayOutputStream()

    test("I-16·I-15: the Demo MID gets one TEST key — exit 0, a 0600 file with five lines in a fixed order") {
        val dir = privateDir()
        issuedFile = dir.resolve("demo-api-key.tsv")
        val originalOut = System.out
        val code = CapturedLogs().use { captured ->
            System.setOut(java.io.PrintStream(stdout, true, StandardCharsets.UTF_8))
            try {
                run(issuedFile, demoMid)
            } finally {
                System.setOut(originalOut)
                logs += captured.lines()
            }
        }
        code shouldBe 0
        PosixFilePermissions.toString(Files.getPosixFilePermissions(issuedFile)) shouldBe "rw-------"
        Files.readAllLines(issuedFile).first() shouldStartWith "# PayCore DEMO API key — NOT OPERATIONAL"
        val lines = dataLines(issuedFile)
        lines.map { it.first } shouldBe listOf("merchantAccountId", "environment", "keyId", "apiKey", "validUntil")
        val values = lines.toMap()
        values["merchantAccountId"] shouldBe demoMid
        values["environment"] shouldBe "TEST"
        Regex("^demo[A-Za-z0-9]{16}$").matches(values.getValue("keyId")) shouldBe true
        Regex("^pc_test_sk_demo[A-Za-z0-9]{16}\\.[A-Za-z0-9_-]{43}$").matches(values.getValue("apiKey")) shouldBe true
        values.getValue("apiKey") shouldStartWith "pc_test_sk_${values["keyId"]}."
        issuances() shouldBe 1
        credentialsOf(demoMid) shouldBe 1
    }

    test("I-16·I-18: stored rows — keyId + SHA-256 only, TEST·ACTIVE·revision 1, valid 7 days, valid_until = lease_until, audit actor and correlation id") {
        val values = dataLines(issuedFile).toMap()
        val keyId = values.getValue("keyId")
        root().use { c ->
            c.prepareStatement(
                "SELECT merchant_account_id, environment, secret_sha256, status, revision, valid_from, valid_until, lease_until, updated_at FROM $db.merchant_api_credential WHERE key_id = ?",
            ).use { ps ->
                ps.setString(1, keyId)
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe demoMid
                    rs.getString(2) shouldBe "TEST"
                    rs.getString(3) shouldBe sha256Hex(values.getValue("apiKey"))
                    rs.getString(4) shouldBe "ACTIVE"
                    rs.getLong(5) shouldBe 1L
                    // DATETIME(3)은 UTC 값이다(앱 세션 UTC) — 시험 연결의 시간대 변환을 피하려고 LocalDateTime으로 읽어 UTC로 해석한다.
                    fun utc(i: Int): Instant = rs.getObject(i, java.time.LocalDateTime::class.java).toInstant(java.time.ZoneOffset.UTC)
                    val from = utc(6)
                    val until = utc(7)
                    Duration.between(from, until) shouldBe Duration.ofDays(7)
                    Duration.between(from, until) shouldBe Duration.parse("PT168H")
                    utc(8) shouldBe until
                    utc(9) shouldBe from
                    values["validUntil"] shouldBe until.toString()
                }
            }
            c.prepareStatement("SELECT id, key_id, merchant_account_id, environment, actor, correlation_id FROM $db.demo_credential_issuance").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                    rs.getString(2) shouldBe keyId
                    rs.getString(3) shouldBe demoMid
                    rs.getString(4) shouldBe "TEST"
                    rs.getString(5) shouldBe "system:demo-credential"
                    rs.getString(6) shouldStartWith "demo-credential-"
                    rs.next() shouldBe false
                }
            }
        }
    }

    test("I-17: the raw key and its secret appear in no stored column, log line or standard output") {
        val apiKey = dataLines(issuedFile).toMap().getValue("apiKey")
        val secret = apiKey.substringAfter('.')
        val storedText = root().use { c ->
            listOf("merchant_api_credential", "demo_credential_issuance").flatMap { table ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT * FROM $db.$table").use { rs ->
                        buildList { while (rs.next()) for (i in 1..rs.metaData.columnCount) add(rs.getString(i).orEmpty()) }
                    }
                }
            }
        }
        storedText.none { it.contains(secret) || it.contains(apiKey) } shouldBe true
        logs.isNotEmpty() shouldBe true
        logs.none { it.contains(secret) || it.contains(apiKey) } shouldBe true
        logs.any { it.contains("operation=demo-credential result=SUCCESS reason=ISSUED") } shouldBe true
        stdout.toString(StandardCharsets.UTF_8).contains(secret) shouldBe false
        DemoCredentialIssueResult.Issued(demoMid, "demoX", apiKey, Instant.EPOCH).toString().contains(secret) shouldBe false
    }

    test("I-18: the existing MerchantAuthenticator accepts the key for the MID in TEST, rejects a tampered secret and rejects it once valid_until is reached") {
        val values = dataLines(issuedFile).toMap()
        val apiKey = values.getValue("apiKey")
        val until = Instant.parse(values.getValue("validUntil"))
        val ok = authenticator.authenticate(apiKey).shouldBeInstanceOf<MerchantAuthenticationResult.Authenticated>().authentication
        ok.merchantAccountId.value shouldBe demoMid
        ok.environment.name shouldBe "TEST"
        ok.credentialKeyId.value shouldBe values["keyId"]
        val tampered = apiKey.dropLast(1) + (if (apiKey.last() == 'A') 'B' else 'A')
        authenticator.authenticate(tampered) shouldBe MerchantAuthenticationResult.Rejected(AuthenticationFailureReason.SECRET_MISMATCH)
        clock.set(until.minusMillis(1))
        authenticator.authenticate(apiKey).shouldBeInstanceOf<MerchantAuthenticationResult.Authenticated>()
        clock.set(until)
        authenticator.authenticate(apiKey) shouldBe MerchantAuthenticationResult.Rejected(AuthenticationFailureReason.NOT_VALID_NOW)
    }

    test("I-16: a rerun is CONDITION_REFUSED (4) ALREADY_ISSUED — still one issuance row and one key, no new file") {
        val dir = privateDir()
        run(dir.resolve("again.tsv"), demoMid) shouldBe 4
        Files.exists(dir.resolve("again.tsv")) shouldBe false
        issuances() shouldBe 1
        credentialsOf(demoMid) shouldBe 1
    }

    test("C5: the write adapters INSERT (persist) — an existing issuance row or keyId is a UniqueConflict, never a silent merge; the TX writes nothing") {
        val existingKeyId = dataLines(issuedFile).toMap().getValue("keyId")
        val tx = org.springframework.transaction.support.TransactionTemplate(transactionManager)
        val now = Instant.parse("2026-10-01T00:00:00Z")
        val onceFailure = runCatching {
            tx.executeWithoutResult {
                issuanceStore.insertOnce(
                    com.paycore.payment.payment.application.required.DemoCredentialIssuance(
                        "demoSECOND0000000001", com.paycore.payment.payment.domain.MerchantAccountId(demoMid), now, "system:demo-credential", "demo-credential-c5",
                    ),
                )
            }
        }.exceptionOrNull().shouldBeInstanceOf<com.paycore.payment.payment.application.required.PersistenceFailure.UniqueConflict>()
        onceFailure.constraint shouldBe com.paycore.payment.payment.application.required.StoreConstraint.DEMO_CREDENTIAL_ISSUANCE
        val keyFailure = runCatching {
            tx.executeWithoutResult {
                projectionWriter.insert(
                    com.paycore.payment.payment.application.required.DemoCredentialProjection(
                        existingKeyId, com.paycore.payment.payment.domain.MerchantAccountId("mid_C5OTHER"), "2".repeat(64), now, now.plusSeconds(60), now.plusSeconds(60),
                    ),
                )
            }
        }.exceptionOrNull().shouldBeInstanceOf<com.paycore.payment.payment.application.required.PersistenceFailure.UniqueConflict>()
        keyFailure.constraint shouldBe com.paycore.payment.payment.application.required.StoreConstraint.MERCHANT_API_CREDENTIAL_KEY_ID
        count("SELECT COUNT(*) FROM $db.demo_credential_issuance WHERE key_id = 'demoSECOND0000000001'") shouldBe 0
        count("SELECT COUNT(*) FROM $db.merchant_api_credential WHERE merchant_account_id = 'mid_C5OTHER'") shouldBe 0
        issuances() shouldBe 1
    }

    test("I-24: the runtime account cannot UPDATE or DELETE either table (1142) but can INSERT a projection row") {
        fun errorOf(c: Connection, sql: String): Int? = try {
            c.createStatement().use { it.executeUpdate(sql) }
            null
        } catch (e: SQLException) {
            e.errorCode
        }
        runtime().use { c ->
            errorOf(c, "UPDATE merchant_api_credential SET status = 'REVOKED' WHERE merchant_account_id = '$demoMid'") shouldBe 1142
            errorOf(c, "DELETE FROM merchant_api_credential WHERE merchant_account_id = '$demoMid'") shouldBe 1142
            errorOf(c, "UPDATE demo_credential_issuance SET actor = 'x'") shouldBe 1142
            errorOf(c, "DELETE FROM demo_credential_issuance") shouldBe 1142
            errorOf(
                c,
                "INSERT INTO merchant_api_credential (key_id, merchant_account_id, environment, secret_sha256, status, valid_from, valid_until, lease_until, revision, updated_at) " +
                    "VALUES ('demoPRIVILEGE0000001', 'mid_PRIV', 'TEST', '${"1".repeat(64)}', 'ACTIVE', '2026-10-01 00:00:00', NULL, '2026-10-01 00:00:01', 1, '2026-10-01 00:00:00')",
            ) shouldBe null
            // 발급 행 INSERT 권한은 있다: 고정 행이 이미 있어 1062(권한 1142가 아니다).
            errorOf(
                c,
                "INSERT INTO demo_credential_issuance (id, key_id, merchant_account_id, environment, issued_at, actor, correlation_id) " +
                    "VALUES (1, 'demoPRIVILEGE0000002', 'mid_PRIV', 'TEST', '2026-10-01 00:00:00', 'system:demo-credential', 'demo-credential-priv')",
            ) shouldBe 1062
        }
    }

    test("I-16: two concurrent issues against an empty issuance table → exactly one Issued, the other LOST; one row and one key") {
        root().use { c ->
            c.createStatement().use {
                it.executeUpdate("DELETE FROM $db.demo_credential_issuance")
                it.executeUpdate("DELETE FROM $db.merchant_api_credential WHERE merchant_account_id = '$demoMid'")
            }
        }
        // 리뷰 M-1: 두 TX는 사전 조회(MID 확인 — entitlement_head FOR SHARE, 발급 행 비잠금 조회)를 마친 뒤 키 생성 지점에서 만난다. {Issued, Lost}를 보려면 둘 다 사전 조회를
        // 통과한 뒤 INSERT해야 하므로 barrier는 head S 잠금을 쥔 뒤일 수밖에 없다. 대신 두 TX가 모두 도착한 순간(barrier action)의 InnoDB 잠금을 찍어
        // entitlement_head에 X 잠금·잠금 대기가 없음을 증명한다(이 Spec은 자기 컨테이너 DB를 쓰고 주기 job 빈이 없다 — I-3).
        val lockSnapshot = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        keys.barrier = CyclicBarrier(2) {
            lockSnapshot.set(
                root().use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery(
                            "SELECT CONCAT(OBJECT_NAME, ':', LOCK_TYPE, ':', LOCK_MODE, ':', LOCK_STATUS) FROM performance_schema.data_locks " +
                                "WHERE OBJECT_SCHEMA = '$db' AND OBJECT_NAME = 'entitlement_head' " +
                                "UNION ALL SELECT CONCAT('WAIT:', REQUESTING_ENGINE_TRANSACTION_ID, '<-', BLOCKING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits",
                        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
                },
            )
        }
        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            (1..2).map { n -> pool.submit<Any> { runCatching { issuer.issue(DemoCredentialIssueCommand(demoMid, "demo-credential-race$n")) }.getOrElse { it } } }
                .map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val described = results.map { r -> if (r is Throwable) generateSequence(r) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" } else r.toString() }
        io.kotest.assertions.withClue("results=$described locksAtBarrier=${lockSnapshot.get()}") {
            results.map { it::class.simpleName } shouldContainExactlyInAnyOrder listOf("Issued", "Lost")
            // 두 TX가 만났을 때 head에는 S(공유) 잠금만 있고 X 잠금·잠금 대기는 없다.
            lockSnapshot.get().filter { it.startsWith("WAIT:") || (it.contains(":RECORD:X") && it.endsWith("GRANTED")) || it.endsWith("WAITING") }.shouldBeEmpty()
        }
        issuances() shouldBe 1
        credentialsOf(demoMid) shouldBe 1
    }

    test("I-16: LIVE against an empty schema → exit 3 and the schema stays empty (the gate runs before Flyway)") {
        root().use { it.createStatement().execute("CREATE DATABASE IF NOT EXISTS demo_credential_gate_probe") }
        val code = runCommand(
            arrayOf(
                "--paycore.payment.command=demo-credential", "--paycore.payment.deployment-mode=LIVE",
                "--PAYCORE_PAYMENT_DB_URL=${PaymentDbAccounts.urlFor(container, "demo_credential_gate_probe")}",
                "--PAYCORE_PAYMENT_DB_USERNAME=root", "--PAYCORE_PAYMENT_DB_PASSWORD=${container.password}",
                "--PAYCORE_PAYMENT_DB_MIGRATION_USERNAME=root", "--PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD=${container.password}",
                "--output=file:/nonexistent/x.tsv", "--merchant-account-id=$demoMid",
            ),
        )
        code shouldBe 3
        count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'demo_credential_gate_probe'") shouldBe 0
    }
})

/** 시험 전용 키 생성기(@Primary): 기본은 운영 생성기, [force]한 값을 먼저 내고, [barrier]가 있으면 두 발급이 같은 지점(사전 조회 뒤·INSERT 전)에서 만나게 한다. */
class ScriptedDemoKeySource : DemoCredentialIssueService.KeySource {
    private val real = DemoCredentialIssueService.SecureRandomKeySource()
    private val forced = ConcurrentLinkedQueue<DemoCredentialIssueService.KeyMaterial>()

    @Volatile
    var barrier: CyclicBarrier? = null

    fun force(material: DemoCredentialIssueService.KeyMaterial) = forced.add(material)

    fun reset() {
        forced.clear()
        barrier = null
    }

    override fun next(): DemoCredentialIssueService.KeyMaterial {
        barrier?.await(30, TimeUnit.SECONDS)
        return forced.poll() ?: real.next()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class DemoCredentialTestConfig {
    @Bean
    @Primary
    fun scriptedDemoKeySource(): ScriptedDemoKeySource = ScriptedDemoKeySource()

    /** 발급·인증이 같은 시험 시계를 본다(만료 경계 확인용). 기본은 기동 시각 — 시험이 옮긴 뒤 되돌린다. */
    @Bean
    @Primary
    fun demoCredentialClock(): MutablePaymentClock = MutablePaymentClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
}
