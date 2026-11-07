package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.application.AcquisitionSealService
import com.paycore.financial.acquiring.application.AcquisitionWaitingBoard
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ACQ-ADMIN-001 B1 조작 채널(실제 Tomcat 포트 + 실제 보안 체인 + MySQL): **D-ACQA-001 조작 전용 키** — 조회 키로 조작 경로 401, 조작 키로 조회 경로 401, 무서명 401
 * (원인 비구별), 조작 키로 실행·결정 조회·기한 확인, 조회 키로 대상 미리 보기. 응답 불변식: 200 = 커밋된 결정, 409 = 같은 승인 ID 다른 내용, 400 = 형식(같은 바이트 같은 판정).
 * 실행의 업무 판정(순액·묶음·불요·경합)은 ReacquisitionExecutionDbSpec이 본다 — 여기서는 채널·응답 계약.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FinancialMySqlContainerConfig::class)
class AdminAcquiringOperationsHttpSpec(
    @LocalServerPort private val port: Int,
    private val inbox: PaymentFactInboxService,
    private val transactionManager: PlatformTransactionManager,
    private val lanes: AcquisitionLaneStore,
    private val files: AcquisitionFileRepository,
    private val membership: AcquisitionMembershipStore,
    private val source: PaymentFactAcquisitionSource,
    private val policies: AcquisitionPolicySource,
    private val results: AcquisitionRecordResultStore,
    private val board: AcquisitionWaitingBoard,
    private val jdbc: JdbcTemplate,
) : BehaviorSpec({

    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()
    val mapper = JsonMapper.builder().build()
    val acq = PaymentFactFixture.nextAcquirerCode()
    val executePath = "/internal/v1/admin/acquiring-operations/reacquisitions"

    val fileId: String by lazy {
        val auth = PaymentFactFixture.authorized(amountMinor = 12_000, acquirerCode = acq, approvedAt = Instant.parse("2026-10-22T01:02:03.456Z"))
        inbox.receive(PaymentFactEnvelopeFixture.inbound(auth, UUID.randomUUID().toString()))
        AcquisitionSealService(transactionManager, lanes, files, membership, source, policies, object : AcquiringClock {
            override fun now(): Instant = Instant.parse("2026-10-22T14:05:00Z")
        }, results, board).seal(acq, "TEST", LocalDate.parse("2026-10-22")).fileId
    }

    fun send(method: String, path: String, body: ByteArray, header: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(15))
        header?.let { builder.header(AdminCallSignature.HEADER, it) }
        if (method == "POST") builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)) else builder.GET()
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun op(method: String, path: String, body: String = "") =
        send(method, path, body.toByteArray(), FinancialAdminChannelFixture.operationHeader(method, path, body.toByteArray()))

    fun read(method: String, path: String, body: String = "") =
        send(method, path, body.toByteArray(), FinancialAdminChannelFixture.springHeader(method, path, body.toByteArray()))

    fun json(r: HttpResponse<String>): JsonNode = mapper.readTree(r.body())

    val approvedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(10)
    val ms = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
    fun body(approvalId: String, evidence: String = "INQ-2026-0001", at: Instant = approvedAt, extra: String = "") =
        """{"approvalId":"$approvalId","originalFileId":"$fileId","items":[{"seq":1,"expectedGeneration":0,"expectedStatus":"REJECTED"}],""" +
            """"requestedBy":"adm_operator_1","approvedBy":"adm_approver_1","reasonCode":"INSTITUTION_REQUESTED","evidenceRef":"$evidence",""" +
            """"approvedAt":"${ms.format(at)}","executeBefore":"${ms.format(at.plus(Duration.ofHours(1)))}","correlationId":"corr-1"$extra}"""

    Given("D-ACQA-001: the operation paths open only with an operation key, the read paths only with a read key") {
        val payload = body("apr_http_keys")
        Then("a read-key signature, no signature and an operation key on a read path are all 401 UNAUTHENTICATED; nothing is recorded") {
            read("POST", executePath, payload).let { (it.statusCode() to json(it).get("error").asString()) } shouldBe (401 to "UNAUTHENTICATED")
            send("POST", executePath, payload.toByteArray(), null).statusCode() shouldBe 401
            val searchPath = "/internal/v1/admin/acquisition-file-searches"
            op("POST", searchPath, """{"fileId":"$fileId"}""").statusCode() shouldBe 401
            op("GET", "/internal/v1/admin/acquisition-files/$fileId").statusCode() shouldBe 401
            jdbc.queryForObject("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = 'apr_http_keys'", Int::class.java) shouldBe 0
        }
    }

    Given("an execution command for a record that has no result yet (the business refusal is a recorded decision)") {
        val first = op("POST", executePath, body("apr_http_awaiting"))
        Then("200 with the recorded REJECTED decision; a replay returns the same decision (replayed); other content is 409 APPROVAL_CONFLICT") {
            first.statusCode() shouldBe 200
            json(first).let { listOf(it.get("decision").asString(), it.get("code").asString(), it.get("replayed").asBoolean(), it.get("resultFileId").isNull) } shouldBe
                listOf("REJECTED", "TARGET_NOT_REACQUIRABLE", false, true)
            json(first).get("contentHash").asString().startsWith("acd1:") shouldBe true
            op("POST", executePath, body("apr_http_awaiting")).let { json(it).get("replayed").asBoolean() } shouldBe true
            op("POST", executePath, body("apr_http_awaiting", evidence = "INQ-OTHER")).let { it.statusCode() to json(it).get("error").asString() } shouldBe
                (409 to "APPROVAL_CONFLICT")
        }
        And("the decision lookup returns it, an unknown approval is NONE, a malformed id is 400") {
            op("GET", "/internal/v1/admin/acquiring-operations/decisions/apr_http_awaiting").let { json(it).get("decision").asString() } shouldBe "REJECTED"
            op("GET", "/internal/v1/admin/acquiring-operations/decisions/apr_http_none").let { it.statusCode() to json(it).get("decision").asString() } shouldBe (200 to "NONE")
            op("GET", "/internal/v1/admin/acquiring-operations/decisions/bad!id").statusCode() shouldBe 400
        }
    }

    Given("strict command bodies (same bytes, same verdict — 400 REQUEST_MALFORMED without echo)") {
        Then("unknown or missing fields, a string number, a null, a trailing token and a non-millisecond time are refused") {
            listOf(
                body("apr_http_bad1", extra = ""","unknown":1"""),
                body("apr_http_bad2").replace(""","correlationId":"corr-1"""", ""),
                body("apr_http_bad3").replace(""""seq":1""", """"seq":"1""""),
                body("apr_http_bad4").replace(""""evidenceRef":"INQ-2026-0001"""", """"evidenceRef":null"""),
                body("apr_http_bad5") + " {}",
                body("apr_http_bad6").replace(ms.format(approvedAt), ms.format(approvedAt).substringBefore('.') + "Z"),
            ).forEach { bad ->
                op("POST", executePath, bad).let { (it.statusCode() to it.body()) } shouldBe (400 to """{"error":"REQUEST_MALFORMED"}""")
            }
        }
    }

    Given("the deadline confirmation on the operation path") {
        val payload = body("apr_http_deadline")
        Then("before the deadline nothing is recorded (NONE, confirmed false); a path/body approval mismatch is 400") {
            op("POST", "/internal/v1/admin/acquiring-operations/decisions/apr_http_deadline/deadline-confirmation", payload).let {
                listOf(it.statusCode(), json(it).get("decision").asString(), json(it).get("confirmed").asBoolean())
            } shouldBe listOf(200, "NONE", false)
            op("POST", "/internal/v1/admin/acquiring-operations/decisions/apr_http_other/deadline-confirmation", payload).statusCode() shouldBe 400
        }
    }

    Given("the re-acquisition target preview on the read path (read key — non-authoritative)") {
        val path = "/internal/v1/admin/acquisition-files/$fileId/reacquisition-targets"
        Then("it answers per seq with eligibility and refusal (no card values); an operation key is 401; a bad body is 400; an unknown file is 404") {
            read("POST", path, """{"seqs":[1,2]}""").let { r ->
                r.statusCode() shouldBe 200
                val items = json(r).get("items")
                listOf(items[0].get("seq").asInt(), items[0].get("eligible").asBoolean(), items[0].get("refusal").asString(), items[0].get("status").asString()) shouldBe
                    listOf(1, false, "TARGET_NOT_REACQUIRABLE", "AWAITING")
                items[1].get("refusal").asString() shouldBe "TARGET_NOT_FOUND"
                items[0].properties().map { it.key }.toSet() shouldBe setOf(
                    "seq", "transactionId", "recordType", "generation", "status", "eligible", "refusal", "approvedAmountMinor", "cancelledAmountMinor", "expectedNetMinor",
                )
            }
            op("POST", path, """{"seqs":[1]}""").statusCode() shouldBe 401
            read("POST", path, """{"seqs":[]}""").statusCode() shouldBe 400
            read("POST", path, """{"seqs":[1,1]}""").statusCode() shouldBe 400
            read("POST", "/internal/v1/admin/acquisition-files/ACQ.$acq.TEST.20261001.001/reacquisition-targets", """{"seqs":[1]}""").statusCode() shouldBe 404
        }
    }
})
