package com.paycore.admin.merchantops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.BusinessQueryService
import com.paycore.admin.merchantops.application.BusinessReadWindow
import com.paycore.admin.merchantops.application.provided.BusinessQueryResult
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import com.paycore.admin.merchantops.application.required.BusinessReadLimits
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-FLOW-001 FLOW-1 ADMIN '계약 진행' 섹션(§6 FLOW-1 — 읽기 전용, 대역 Port, Spring 없음):
 * - Coordinator 결정 Q1(D-FLOW-004 ④ 적용): 사업자 상세 요청 1회 = 기존 `BUSINESS_VIEWED` 감사 **1행**. ADMIN은 상세 열람 처리 안에서 MASTER 섹션 조회를 함께 부르고
 *   섹션 전용·조회별 감사를 만들지 않는다. 섹션 조회 실패(Unavailable·NotFound)는 상세를 실패시키지 않고 섹션만 `available=false`(fail-safe 표시, 쓰기 없음).
 * - 리뷰 m-11: 계약 담당자·전이 행위자 표시명은 ADMIN Application이 자기 계정 현재값에서 채운다. `login_name`(D-ADMIN1-017)·관리자 내부 키(`adm_`)·MASTER 행위자 참조 원문은
 *   화면 응답에 싣지 않는다. 이관 건(§4A.9)은 담당자 없음·행위자 SYSTEM(R-6 — 화면이 '이관'으로 표시).
 * - 근거 참조 원문은 사업자 조회 역할(OPERATOR·APPROVER) 전체에 그대로(D-FLOW-004 ④). 상세 실패·403·창 초과에서는 섹션을 부르지 않는다.
 */
class BusinessContractProgressSpec : BehaviorSpec({

    val viewerId = "adm_01JC00000000000000000000V1"
    val managerId = "adm_01JC00000000000000000000V2"
    val biz = "biz_01JC0000000000000000000001"
    val ctx = AdminRequestContext("req-cp", "127.0.0.1")
    val json = JsonMapper.builder().build()

    fun transition(seq: Int, from: String?, to: String, actor: String, migrated: Boolean = false, documentRef: String? = null) = linkedMapOf<String, Any?>(
        "transitionSeq" to seq.toLong(), "fromStatus" to from, "toStatus" to to, "migrated" to migrated, "documentRef" to documentRef, "actorRef" to actor,
        "occurredAt" to "2026-11-25T01:00:00.000Z",
    )

    /** MASTER 섹션 문서(golden `contract-intake-section-v2`와 같은 모양 — 모양 검사를 통과한 값으로 Gateway가 돌려주는 형태). FLOW-2b: 종류·대상·사업자 계약 칸, `TERMINATED` 없음. */
    fun version(no: Int, migrated: Boolean, actor: String, startDate: String?, endDate: String?, documentRef: String?, closedAt: String?) = linkedMapOf<String, Any?>(
        "versionNo" to no.toLong(), "migrated" to migrated, "originIntakeSeq" to no.toLong(), "startsAt" to (if (migrated) "2026-11-21T03:00:00.000Z" else "2026-11-26T15:00:00.000Z"),
        "startDate" to startDate, "endDate" to endDate, "services" to listOf("CARD_PG_PAYMENT"), "documentRef" to documentRef, "closedAt" to closedAt,
        "createdAt" to (if (migrated) "2026-11-21T03:00:00.000Z" else "2026-11-26T01:00:00.000Z"), "createdBy" to actor,
    )

    fun contract(status: String = "ACTIVE") = linkedMapOf<String, Any?>(
        "contractSeq" to 1L, "migrated" to true, "status" to status, "applyingVersionNo" to 1L, "scheduledVersionNo" to 2L, "linkedMerchantCount" to 2L,
        "createdAt" to "2026-11-21T03:00:00.000Z", "createdBy" to "system:migration",
        "versions" to listOf(
            version(1, true, "system:migration", null, null, null, "2026-11-26T15:00:00.000Z"),
            version(2, false, "admin:$managerId", "2026-11-27", "2027-11-26", "DEMO-DOC-0001", null),
        ),
        "events" to listOf(
            linkedMapOf<String, Any?>("eventSeq" to 1L, "kind" to "SUSPEND", "evidenceRef" to null, "actorRef" to "admin:$viewerId", "occurredAt" to "2026-11-26T02:00:00.000Z"),
        ),
    )

    fun section(businessContract: Map<String, Any?>? = contract()) = linkedMapOf<String, Any?>(
        "businessId" to biz,
        "judgedAt" to "2026-11-26T01:00:00.000Z",
        "businessContract" to businessContract,
        "intakes" to listOf(
            linkedMapOf<String, Any?>(
                "intakeSeq" to 2L, "status" to "DOCUMENT_RECEIVED", "kind" to "CHANGE", "targetContractSeq" to 1L, "migrated" to false, "receivedOn" to "2026-11-25",
                "intakeChannel" to "PHONE", "contractManager" to "admin:$managerId", "desiredServices" to listOf("CARD_AUTH"), "version" to 1L,
                "createdAt" to "2026-11-25T01:00:00.000Z", "updatedAt" to "2026-11-25T02:00:00.000Z",
                "transitions" to listOf(transition(1, null, "CONSULTING", "admin:$viewerId"), transition(2, "CONSULTING", "DOCUMENT_RECEIVED", "admin:$managerId")),
            ),
            linkedMapOf<String, Any?>(
                "intakeSeq" to 1L, "status" to "COMPLETED", "kind" to "NEW", "targetContractSeq" to null, "migrated" to true, "receivedOn" to null, "intakeChannel" to null,
                "contractManager" to null, "desiredServices" to emptyList<String>(), "version" to 0L, "createdAt" to "2026-10-01T00:00:00.000Z",
                "updatedAt" to "2026-10-01T00:00:00.000Z",
                "transitions" to listOf(transition(1, null, "COMPLETED", "system:migration", migrated = true)),
            ),
        ),
    )

    class Fixture(roles: Set<String> = setOf("OPERATOR"), businessReads: Int = 30, var now: Instant = Instant.parse("2026-11-25T15:30:00Z")) {
        val audits = mutableListOf<AuditRecord>()
        val calls = mutableListOf<String>()
        var failAudit = false
        var failNames = false
        var detailReply: MasterCallResult = MasterCallResult.Found(linkedMapOf("business" to linkedMapOf("businessId" to "biz_01JC0000000000000000000001")))
        var sectionReply: MasterCallResult = MasterCallResult.NotFound
        val accounts = mutableMapOf(
            "adm_01JC00000000000000000000V1" to AdminSessionView("adm_01JC00000000000000000000V1", "조회 담당", "ACTIVE", 1, 1, roles),
            // 계약 담당자 계정이 나중에 비활성이 되어도 기록은 그대로 보인다(§4A.6 [구현 관례]) — 표시명은 계정 현재값.
            "adm_01JC00000000000000000000V2" to AdminSessionView("adm_01JC00000000000000000000V2", "영업 담당", "DISABLED", 1, 1, setOf("OPERATOR")),
        )
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val sessions = object : AdminSessionQuery {
            override fun current(actorId: String): AdminSessionView? {
                // 앞단 판정(조회자)은 정상, 표시명 조회(다른 계정)만 실패 주입.
                if (failNames && actorId != "adm_01JC00000000000000000000V1") throw IllegalStateException("injected account read failure")
                return accounts[actorId]
            }
            override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
        }
        val recorder = object : AdminAuditRecorder {
            override fun record(record: AuditRecord) {
                if (failAudit) throw IllegalStateException("injected audit failure")
                audits += record
            }
        }
        val metrics = object : MerchantOpsMetrics {
            override fun rateLimited(kind: String) = Unit
            override fun command(operation: String, result: String) = Unit
            override fun commandUnknown(operation: String) = Unit
        }
        val gateway = object : MasterBusinessGateway {
            override fun search(criteria: BusinessSearchCriteria): MasterCallResult = error("not used")
            override fun lookup(registrationNumber: String): MasterCallResult = error("not used")
            override fun detail(businessId: String): MasterCallResult = detailReply.also { calls += "detail" }
            override fun contractIntakes(businessId: String): MasterCallResult = sectionReply.also { calls += "contract-intakes:$businessId" }
            override fun history(businessId: String, afterSeq: Long): MasterCallResult = error("not used")
            override fun criticalChangeProposal(command: MasterBusinessCommand): MasterBusinessCommandResult = error("not used")
            override fun approvalState(businessId: String): MasterCallResult = error("not used")
            override fun proposalOriginal(businessId: String, proposalId: String): MasterCallResult = error("not used")
            override fun changeEvidence(businessId: String, historyId: Long): MasterCallResult = error("not used")
            override fun command(command: MasterBusinessCommand): MasterBusinessCommandResult = error("not used")
        }
        val window = BusinessReadWindow(object : BusinessReadLimits {
            override val businessReadsPerMinute = businessReads
        }) { 0L }
        val query = BusinessQueryService(tx, sessions, recorder, gateway, window, metrics, { now }, null) // BIZ-CRIT-APPROVAL-001 S3: 대기 SPI 없음(인자 보강)
        fun auditRows() = audits.map { "${it.action}|${it.resultCode}|${it.detail}|${it.targetRef}" }
    }

    @Suppress("UNCHECKED_CAST")
    fun progressOf(result: BusinessQueryResult): Map<String, Any?> =
        result.shouldBeInstanceOf<BusinessQueryResult.Found>().document["contractProgress"] as Map<String, Any?>

    Given("FLOW-1 the detail view carries the contract-progress section (Q1 — one BUSINESS_VIEWED row per detail request)") {
        Then("section found → contractProgress available, intakes newest first with transitions; one MASTER section call; exactly one BUSINESS_VIEWED OK row") {
            val f = Fixture()
            f.sectionReply = MasterCallResult.Found(section())
            val result = f.query.detail(viewerId, biz, ctx)
            val progress = progressOf(result)
            progress["available"] shouldBe true
            @Suppress("UNCHECKED_CAST")
            val intakes = progress["intakes"] as List<Map<String, Any?>>
            intakes.map { it["intakeSeq"] } shouldBe listOf(2L, 1L)
            f.calls shouldBe listOf("detail", "contract-intakes:$biz")
            f.auditRows() shouldBe listOf("BUSINESS_VIEWED|OK|null|$biz")
            // 기존 상세 문서는 그대로(키 추가만).
            result.shouldBeInstanceOf<BusinessQueryResult.Found>().document["business"] shouldBe linkedMapOf("businessId" to biz)
        }

        Then("m-11: the contract manager and ADMIN actors get the ADMIN display name (account current value, even when the account is no longer active); raw refs, adm_ keys and login names are absent") {
            val f = Fixture()
            f.sectionReply = MasterCallResult.Found(section())
            val progress = progressOf(f.query.detail(viewerId, biz, ctx))
            @Suppress("UNCHECKED_CAST")
            val intakes = progress["intakes"] as List<Map<String, Any?>>
            val open = intakes[0]
            open.keys shouldBe setOf(
                // 기대값 변경(FLOW-2b §4B.11 — 추가형): kind·targetContractSeq 추가(기존 키 그대로).
                "intakeSeq", "status", "kind", "targetContractSeq", "migrated", "receivedOn", "intakeChannel", "contractManagerName", "desiredServices", "version", "createdAt",
                "updatedAt", "transitions",
            )
            open["kind"] shouldBe "CHANGE"
            open["targetContractSeq"] shouldBe 1L
            open["contractManagerName"] shouldBe "영업 담당"
            @Suppress("UNCHECKED_CAST")
            val moves = open["transitions"] as List<Map<String, Any?>>
            moves[0].keys shouldBe setOf("transitionSeq", "fromStatus", "toStatus", "migrated", "documentRef", "actorKind", "actorName", "occurredAt")
            moves.map { "${it["actorKind"]}|${it["actorName"]}" } shouldBe listOf("ADMIN|조회 담당", "ADMIN|영업 담당")
            val text = json.writeValueAsString(progress)
            listOf("adm_", "admin:", "actorRef", "contractManager\"", "loginName", "businessId", biz).forEach { needle ->
                withClue(needle) { text shouldNotContain needle }
            }
        }

        Then("FLOW-3 Coordinator 결정: a Demo-seed intake (contract manager system:demo-seed) shows the manager as 'Demo seed' without an account lookup; the raw ref is absent") {
            val f = Fixture()
            f.failNames = true // 계정 조회를 하면 실패한다 — Demo seed 담당자는 계정 조회 없이 고정 표시.
            val demo = section(businessContract = null).also { s ->
                @Suppress("UNCHECKED_CAST")
                val intakes = s["intakes"] as List<MutableMap<String, Any?>>
                intakes[0]["contractManager"] = "system:demo-seed"
                intakes[0]["transitions"] = listOf(transition(1, null, "CONSULTING", "system:demo-seed"), transition(2, "CONSULTING", "DOCUMENT_RECEIVED", "system:demo-seed"))
            }
            f.sectionReply = MasterCallResult.Found(demo)
            val progress = progressOf(f.query.detail(viewerId, biz, ctx))
            progress["available"] shouldBe true
            @Suppress("UNCHECKED_CAST")
            val open = (progress["intakes"] as List<Map<String, Any?>>)[0]
            open["contractManagerName"] shouldBe "Demo seed"
            json.writeValueAsString(progress) shouldNotContain "system:demo-seed"
        }

        Then("R-6: a migrated intake has no manager name and its migration row is a SYSTEM actor without name (FLOW-2b: no termination transition — D-GOV-018 ②ⓑ)") {
            val f = Fixture(roles = setOf("APPROVER"))
            f.sectionReply = MasterCallResult.Found(section())
            @Suppress("UNCHECKED_CAST")
            val migrated = (progressOf(f.query.detail(viewerId, biz, ctx))["intakes"] as List<Map<String, Any?>>)[1]
            migrated["migrated"] shouldBe true
            migrated["kind"] shouldBe "NEW"
            migrated["contractManagerName"] shouldBe null
            @Suppress("UNCHECKED_CAST")
            val moves = migrated["transitions"] as List<Map<String, Any?>>
            // 기대값 변경(FLOW-2b — TERMINATED 제거, §4B.15 강화): 옛 두 번째 행 `COMPLETED → TERMINATED`(DEMO-TERM-0001)는 없다. 해지는 사업자 계약 사건으로만 온다.
            moves.map { listOf(it["toStatus"], it["migrated"], it["actorKind"], it["actorName"], it["documentRef"]) } shouldBe listOf(
                listOf("COMPLETED", true, "SYSTEM", null, null),
            )
        }

        Then("an ADMIN actor whose account is unknown → name null (no raw key fallback)") {
            val f = Fixture()
            f.accounts.remove(managerId)
            f.sectionReply = MasterCallResult.Found(section())
            @Suppress("UNCHECKED_CAST")
            val open = (progressOf(f.query.detail(viewerId, biz, ctx))["intakes"] as List<Map<String, Any?>>)[0]
            open["contractManagerName"] shouldBe null
            @Suppress("UNCHECKED_CAST")
            ((open["transitions"] as List<Map<String, Any?>>)[1]).let { listOf(it["actorKind"], it["actorName"]) } shouldBe listOf("ADMIN", null)
        }

        Then("fail-safe: section Unavailable or NotFound → detail still Found with contractProgress available=false and no intakes; audit is the same single OK row") {
            listOf(MasterCallResult.Unavailable("TIMEOUT"), MasterCallResult.Unavailable("RESPONSE_UNREADABLE"), MasterCallResult.NotFound).forEach { reply ->
                val f = Fixture()
                f.sectionReply = reply
                withClue(reply) {
                    progressOf(f.query.detail(viewerId, biz, ctx)) shouldBe linkedMapOf("available" to false, "intakes" to emptyList<Any>())
                    f.auditRows() shouldBe listOf("BUSINESS_VIEWED|OK|null|$biz")
                    f.calls shouldBe listOf("detail", "contract-intakes:$biz")
                }
            }
        }

        Then("fail-safe: a failure while filling display names (ADMIN account read throws) → section available=false; the detail is still returned with its single OK row") {
            val f = Fixture()
            f.sectionReply = MasterCallResult.Found(section())
            f.failNames = true
            progressOf(f.query.detail(viewerId, biz, ctx)) shouldBe linkedMapOf("available" to false, "intakes" to emptyList<Any>())
            f.auditRows() shouldBe listOf("BUSINESS_VIEWED|OK|null|$biz")
        }

        Then("detail NotFound / Unavailable → the section is not called; the existing audit rows are unchanged") {
            val f = Fixture()
            f.detailReply = MasterCallResult.NotFound
            f.query.detail(viewerId, biz, ctx) shouldBe BusinessQueryResult.NotFound
            f.detailReply = MasterCallResult.Unavailable("TIMEOUT")
            f.query.detail(viewerId, biz, ctx).shouldBeInstanceOf<BusinessQueryResult.Unavailable>()
            f.calls shouldBe listOf("detail", "detail")
            f.auditRows() shouldBe listOf("BUSINESS_VIEWED|NOT_FOUND|null|$biz", "BUSINESS_VIEWED|UNAVAILABLE|null|$biz")
        }

        Then("403 (VIEWER) and the read window → MASTER is not called (detail nor section); a section call does not consume a second window slot") {
            val viewer = Fixture(roles = setOf("VIEWER"))
            viewer.query.detail(viewerId, biz, ctx) shouldBe BusinessQueryResult.Forbidden
            viewer.calls.shouldBeEmpty()
            val f = Fixture(businessReads = 1)
            f.sectionReply = MasterCallResult.Found(section())
            f.query.detail(viewerId, biz, ctx).shouldBeInstanceOf<BusinessQueryResult.Found>()
            f.query.detail(viewerId, biz, ctx) shouldBe BusinessQueryResult.RateLimited
            f.calls shouldBe listOf("detail", "contract-intakes:$biz")
            f.auditRows() shouldBe listOf("BUSINESS_VIEWED|OK|null|$biz", "BUSINESS_VIEWED|RATE_LIMITED|null|$biz")
        }

        Then("audit failure → AUDIT_UNAVAILABLE and neither the detail nor the section (document refs) is returned") {
            val f = Fixture()
            f.failAudit = true
            f.sectionReply = MasterCallResult.Found(section())
            f.query.detail(viewerId, biz, ctx) shouldBe BusinessQueryResult.AuditUnavailable
        }

        Then("the Found document toString does not leak the section (document refs, names)") {
            val f = Fixture()
            f.sectionReply = MasterCallResult.Found(section())
            val text = f.query.detail(viewerId, biz, ctx).toString()
            text shouldNotContain "DEMO-DOC-0001"
            text shouldNotContain "영업 담당"
        }
    }

    Given("FLOW-2b the business-contract column and the ADMIN today (§4B.11 1 · §4A.6 n-4 · MINOR-4)") {
        Then("the business contract is reshaped: status, applying / scheduled version, linked merchant count, versions with their record time (createdAt) and actor names, events; no raw refs") {
            val f = Fixture()
            f.sectionReply = MasterCallResult.Found(section())
            val progress = progressOf(f.query.detail(viewerId, biz, ctx))
            progress.keys shouldBe setOf("available", "today", "businessContract", "intakes")
            @Suppress("UNCHECKED_CAST")
            val c = progress["businessContract"] as Map<String, Any?>
            c.keys shouldBe setOf("contractSeq", "migrated", "status", "applyingVersionNo", "scheduledVersionNo", "linkedMerchantCount", "createdAt", "versions", "events")
            listOf(c["contractSeq"], c["migrated"], c["status"], c["applyingVersionNo"], c["scheduledVersionNo"], c["linkedMerchantCount"]) shouldBe
                listOf(1L, true, "ACTIVE", 1L, 2L, 2L)
            @Suppress("UNCHECKED_CAST")
            val versions = c["versions"] as List<Map<String, Any?>>
            versions[0].keys shouldBe setOf(
                "versionNo", "migrated", "startsAt", "startDate", "endDate", "services", "documentRef", "closedAt", "createdAt", "actorKind", "actorName",
            )
            versions.map { listOf(it["versionNo"], it["migrated"], it["startDate"], it["endDate"], it["documentRef"], it["closedAt"], it["createdAt"], it["actorKind"], it["actorName"]) } shouldBe
                listOf(
                    listOf(1L, true, null, null, null, "2026-11-26T15:00:00.000Z", "2026-11-21T03:00:00.000Z", "SYSTEM", null),
                    listOf(2L, false, "2026-11-27", "2027-11-26", "DEMO-DOC-0001", null, "2026-11-26T01:00:00.000Z", "ADMIN", "영업 담당"),
                )
            versions[1]["services"] shouldBe listOf("CARD_PG_PAYMENT")
            @Suppress("UNCHECKED_CAST")
            val events = c["events"] as List<Map<String, Any?>>
            events.map { listOf(it["eventSeq"], it["kind"], it["evidenceRef"], it["actorKind"], it["actorName"], it["occurredAt"]) } shouldBe
                listOf(listOf(1L, "SUSPEND", null, "ADMIN", "조회 담당", "2026-11-26T02:00:00.000Z"))
            val text = json.writeValueAsString(progress)
            listOf("adm_", "admin:", "system:", "createdBy", "actorRef", "originIntakeSeq", "judgedAt", biz).forEach { needle ->
                withClue(needle) { text shouldNotContain needle }
            }
        }
        Then("no business contract → businessContract null (the screen shows '사업자 계약 없음')") {
            val f = Fixture()
            f.sectionReply = MasterCallResult.Found(section(businessContract = null))
            progressOf(f.query.detail(viewerId, biz, ctx))["businessContract"] shouldBe null
        }
        Then("n-4: today = the ADMIN clock instant converted to Asia/Seoul (KST midnight boundary), never the browser") {
            listOf(
                "2026-11-25T14:59:59.999Z" to "2026-11-25",
                "2026-11-25T15:00:00Z" to "2026-11-26",
                "2026-11-25T15:30:00Z" to "2026-11-26",
            ).forEach { (instant, today) ->
                val f = Fixture(now = Instant.parse(instant))
                f.sectionReply = MasterCallResult.Found(section())
                withClue(instant) { progressOf(f.query.detail(viewerId, biz, ctx))["today"] shouldBe today }
            }
        }
        Then("fail-safe form is unchanged: {available=false, intakes=[]} (no today, no businessContract)") {
            val f = Fixture()
            f.sectionReply = MasterCallResult.NotFound
            progressOf(f.query.detail(viewerId, biz, ctx)) shouldBe linkedMapOf("available" to false, "intakes" to emptyList<Any>())
        }
    }
})
