package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.raw
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.MasterFaultInjector
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * BIZ-CRIT-APPROVAL-001 S2 MASTER API(§5.1 — 실제 보안 필터 체인 + MVC + MySQL): 제안 명령(응답 모양·저장된 거절·규칙 위반 400(§7.1 이동)·번호 필드 400·비정규 요청자 400),
 * 근거 경로(서명 없음 401, 성공·재생, `mop1` 불일치 400, 번호 필드 400, 기한 확인·결정 조회가 같은 바이트를 받는다), 조회(approval-state 모양, 제안·원문·1인 근거 — 사업자 범위 404).
 * 기대값은 명세 리터럴.
 */
@MasterDbTest
class BusinessCriticalChangeApiSpec(
    context: WebApplicationContext,
    private val container: MySQLContainer,
    private val faults: MasterFaultInjector,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    beforeSpec { faults.reset(); clock.reset() }
    afterAny { faults.reset(); clock.reset() }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun post(path: String, body: String, signed: Boolean = true): MvcResult {
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body.toByteArray(Charsets.UTF_8))
        return mvc.perform(if (signed) b.header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, body.toByteArray(Charsets.UTF_8))) else b).andReturn()
    }
    fun get(path: String): MvcResult = mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path))).andReturn()
    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun MvcResult.tree(): JsonNode = json.readTree(body())
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    val proposals = "/internal/v1/admin/businesses/commands/critical-change-proposals"
    val apply = "/internal/v1/admin/merchant-operations/business-critical-changes"
    fun base(id: String) = "/internal/v1/admin/businesses/$id"
    val requester = Submission(account = ApprovalEvidenceFixtures.REQUESTER.value.removePrefix("admin:"))

    fun created(status: String = "REGISTERED", content: Map<String, Any?> = BusinessBodies.corporation()): String {
        val r = post(BusinessBodies.CREATE, BusinessBodies.create(status = status, number = BusinessFixtures.registrationNumber().value, content = content))
        withClue(r.body()) { r.tree().get("outcome").asString() shouldBe "SUCCEEDED" }
        return r.tree().get("reference").asString()
    }

    fun proposalBody(id: String, version: Any? = 0, s: Submission = Submission(account = requester.actor.removePrefix("admin:")), reason: String? = "상호 변경 신고",
        content: Map<String, Any?> = BusinessBodies.corporation("새 법인 상호"), evidence: Any? = "BIZ-DOC/2026-0001#p1", extra: Map<String, Any?> = emptyMap()): String =
        BusinessBodies.json(LinkedHashMap(BusinessBodies.updateMap(s, id, version, reason, content)).apply { if (evidence != BusinessBodies.REMOVE) put("evidenceRef", evidence); putAll(extra) })

    fun applyFields(id: String, baseVersion: String, proposalId: String, digest: String, a: Approval = Approval()): List<Pair<String, String>> {
        val op = AdminOperation.APPLY_BUSINESS_CRITICAL_CHANGE
        val fp = a.fingerprint(op, listOf(id, baseVersion, proposalId, digest))
        return listOf(
            "idempotencyKey" to a.idempotencyKey, "correlationId" to a.correlationId, "approvalId" to a.approvalId, "requestedBy" to a.requestedBy,
            "approvedBy" to a.approvedBy, "approvedAt" to a.approvedAt, "executeBefore" to a.executeBefore, "evidenceRef" to a.evidenceRef, "approvalFingerprint" to fp,
            "operation" to op.code, "businessId" to id, "baseVersion" to baseVersion, "proposalId" to proposalId, "contentDigest" to digest,
        ).map { (k, v) -> k to str(v) }
    }

    Given("§5.1 proposal command") {
        Then("SUCCEEDED carries the proposal summary (id, base version, h1 digest, fields, flags, requester); replay is the same; nothing changes on the business") {
            val id = created()
            val body = proposalBody(id)
            val r = post(proposals, body)
            withClue(r.body()) { r.response.status shouldBe 200 }
            val t = r.tree()
            val pid = t.get("reference").asString().also { it shouldMatch Regex("^bcp_[0-9A-HJKMNP-TV-Z]{26}$") }
            listOf(t.get("outcome").asString(), t.get("replayed").asBoolean()) shouldBe listOf("SUCCEEDED", false)
            val p = t.get("proposal")
            p.propertyNames().toList() shouldBe listOf("proposalId", "baseVersion", "contentDigest", "changedFields", "representativeChanged", "subjectCorrection", "requestedBy")
            listOf(p.get("proposalId").asString(), p.get("baseVersion").asLong(), p.get("changedFields").toString(), p.get("representativeChanged").asBoolean(),
                p.get("subjectCorrection").asBoolean(), p.get("requestedBy").asString()) shouldBe listOf(pid, 0L, """["displayName"]""", false, false, requester.actor)
            p.get("contentDigest").asString() shouldMatch Regex("^h1:[0-9a-f]{64}$")
            post(proposals, body).tree().get("replayed").asBoolean() shouldBe true
            get(base(id)).tree().get("business").get("version").asLong() shouldBe 0L
        }

        Then("stored rejection keeps the common shape without reference; REGISTERED with reps removed and a reason → 400 BUSINESS_VALIDATION_FAILED (moved from BusinessProfileApiSpec — §7.1)") {
            val draft = created(status = "DRAFT")
            post(proposals, proposalBody(draft)).body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED","replayed":false}"""
            val id = created()
            val noReps = BusinessBodies.with(BusinessBodies.corporation("새 법인 상호"), "representatives", emptyList<Any>())
            post(proposals, proposalBody(id, content = noReps)).body() shouldBe """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"representatives","code":"REQUIRED"}]}"""
        }

        Then("structure: registrationNumber in the body, a missing evidenceRef or a non-canonical requester → 400 REQUEST_MALFORMED; a card-number-shaped evidenceRef → 400 fieldErrors; nothing stored") {
            val id = created()
            val s = Submission(account = requester.actor.removePrefix("admin:"))
            listOf(
                post(proposals, proposalBody(id, s = s, extra = mapOf("registrationNumber" to "1234567890"))).body(),
                post(proposals, proposalBody(id, s = s, evidence = BusinessBodies.REMOVE)).body(),
                post(proposals, proposalBody(id, s = Submission(account = "operator-1"))).body(),
            ).toSet() shouldBe setOf("""{"error":"REQUEST_MALFORMED"}""")
            post(proposals, proposalBody(id, s = s, evidence = "4111111111111111")).body() shouldBe
                """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"evidenceRef","code":"CARD_NUMBER_PATTERN"}]}"""
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 0L
        }
    }

    Given("§5.1 approved execution and shared recovery endpoints") {
        Then("unsigned → 401; signed → SUCCEEDED reference businessId, replay; deadline confirmation and decision accept the same stored bytes; mop1 mismatch or a number field → 400") {
            val id = created()
            val t = post(proposals, proposalBody(id)).tree()
            val pid = t.get("reference").asString()
            val digest = t.get("proposal").get("contentDigest").asString()
            val a = Approval()
            val body = raw(applyFields(id, "0", pid, digest, a))
            post(apply, body, signed = false).response.status shouldBe 401
            post(apply, body).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$id","replayed":false}"""
            post(apply, body).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$id","replayed":true}"""
            post(MerchantApprovalBodies.DEADLINE_CONFIRMATIONS, body).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$id","replayed":true}"""
            val decision = post(MerchantApprovalBodies.APPROVAL_DECISIONS, """{"approvalId":"${a.approvalId}","correlationId":"${a.correlationId}"}""").tree()
            listOf(decision.get("decision").asString(), decision.get("operation").asString(), decision.get("outcome").asString()) shouldBe
                listOf("DECIDED", "ApplyBusinessCriticalChange", "SUCCEEDED")
            get(base(id)).tree().get("business").get("businessName").asString() shouldBe "새 법인 상호"

            val other = Approval()
            val tampered = applyFields(id, "1", pid, digest, other).map { (k, v) -> if (k == "baseVersion") k to str("0") else k to v }
            post(apply, raw(tampered)).body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
            post(apply, raw(applyFields(id, "1", pid, digest, Approval()) + ("registrationNumber" to str("1234567890")))).body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
        }
    }

    Given("MINOR-1 evidence rejections on this endpoint are stored with a REJECTED evidence row and change nothing") {
        Then("APPROVAL_SAME_ACTOR, APPROVAL_WINDOW_INVALID (window + 1 ms) and APPROVAL_EXPIRED (MASTER clock past executeBefore)") {
            fun case(a: Approval, clockAt: java.time.Instant? = null): List<Any?> {
                val id = created()
                val t = post(proposals, proposalBody(id)).tree()
                val pid = t.get("reference").asString()
                val digest = t.get("proposal").get("contentDigest").asString()
                clockAt?.let(clock::set)
                val body = post(apply, raw(applyFields(id, "0", pid, digest, a))).body()
                clock.reset()
                return listOf(
                    body,
                    OnboardingFixture.runtime(container).use { it.scalar("SELECT outcome FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId)?.toString() },
                    get(base(id)).tree().get("business").get("version").asLong(),
                )
            }
            listOf(
                case(Approval(approvedBy = ApprovalEvidenceFixtures.REQUESTER.value)),
                case(Approval(executeBefore = "2026-10-10T01:59:00.001Z")),
                case(Approval(), clockAt = java.time.Instant.parse("2026-10-09T03:00:00.000Z")),
            ) shouldBe listOf("APPROVAL_SAME_ACTOR", "APPROVAL_WINDOW_INVALID", "APPROVAL_EXPIRED").map {
                listOf("""{"outcome":"REJECTED","rejection":"$it","replayed":false}""", "REJECTED", 0L)
            }
        }
    }

    Given("§5.1 queries (business-scoped)") {
        Then("approval-state is exactly {version, lifecycle, registrationStatus}; proposal view and original 404 under another business; the 1-person evidence lookup is scoped and carries the recorder") {
            val id = created()
            get("${base(id)}/approval-state").body() shouldBe """{"version":0,"lifecycle":"ACTIVE","registrationStatus":"REGISTERED"}"""
            val pid = post(proposals, proposalBody(id)).tree().get("reference").asString()
            val view = get("${base(id)}/critical-change-proposals/$pid")
            view.response.status shouldBe 200
            view.tree().get("displayName").asString() shouldBe "새 법인 상호"
            view.body() shouldNotContain "1101110000001"
            get("${base(id)}/critical-change-proposals/$pid/original").tree().get("content").get("registration").get("corporateRegistrationNumber").asString() shouldBe "1101110000001"
            val other = created()
            listOf(
                get("${base(other)}/critical-change-proposals/$pid").response.status,
                get("${base(other)}/critical-change-proposals/$pid/original").response.status,
                get("${base(BusinessFixtures.individualRegistered().business.id.value)}/approval-state").response.status,
            ) shouldBe listOf(404, 404, 404)

            val draft = created(status = "DRAFT")
            val s = Submission()
            val update = BusinessBodies.json(LinkedHashMap(BusinessBodies.updateMap(s, draft, 0, "등록 정보 수정", BusinessBodies.corporation("고친 상호"))).apply { put("evidenceRef", "REG-DOC/9") })
            post(BusinessBodies.UPDATE, update).tree().get("outcome").asString() shouldBe "SUCCEEDED"
            val historyId = count("SELECT MAX(id) FROM master_change_history WHERE aggregate_id = ?", draft)
            get("${base(draft)}/business-change-evidence/$historyId").body() shouldBe
                """{"historyId":$historyId,"evidenceRef":"REG-DOC/9","recordedBy":"${s.actor}","recordedAt":"${get("${base(draft)}/business-change-evidence/$historyId").tree().get("recordedAt").asString()}"}"""
            get("${base(other)}/business-change-evidence/$historyId").response.status shouldBe 404
            // 이력 탭 응답에 근거 원문이 없다.
            get(BusinessBodies.history(draft)).body() shouldNotContain "REG-DOC/9"
        }
    }
})
