package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AdminAuditTrail
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.BusinessPermissions
import com.paycore.admin.merchantops.application.ContractIntakeCommandService
import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessProfileResult
import com.paycore.admin.merchantops.application.provided.ContractIntakeOperation
import com.paycore.admin.merchantops.application.provided.ContractIntakeRequest
import com.paycore.admin.merchantops.application.required.BusinessOpsMetrics
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterContractIntakeCommand
import com.paycore.admin.merchantops.application.required.MasterContractIntakeGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-FLOW-001 FLOW-2b ADMIN 계약 접수 건 명령 Use Case(§4B.7·§4B.11 표 2b 행·§4A.3a·§4A.4·§4A.6 — 대역 Port, Spring 없음, 명시 Demo fixture):
 * - 권한: OPERATOR만 명령(APPROVER·VIEWER → 403 + 감사 `FORBIDDEN`, MASTER 미호출 — BR-BIZ-041 관례·D-BIZ-006 ①), 앞단 실패는 감사 없음.
 * - ADMIN 1차 검사(400 + fieldErrors, MASTER·감사 없음 — 사업자 수정 관례): '사유 및 비고' 1~200자·카드번호 패턴(§4A.3a), 되돌리기·계약 불가 사유 필수, 근거 참조 형식·카드번호(§4A.4),
 *   접수일 미래(ADMIN 시계 KST — n-4), 시작일 과거·종료일 < 시작일(§4B.3), '영업대행사' 경로 NOT_ALLOWED(FLOW-4 전), 계약 담당자 ACTIVE OPERATOR(n-3 — ADMIN 계정).
 * - 같은 submissionId 재전송(앞선 시도의 `REQUESTED` 감사가 있음)은 **시각 의존 검사(접수일·시작일)만** 다시 하지 않는다(n-3 — 저장된 결과 회수). 계약 담당자 ACTIVE OPERATOR 검사는
 *   재전송에도 항상 한다(FLOW-2b 리뷰 MINOR-1 — Coordinator 결정, 가역).
 * - 감사: 시도 `REQUESTED` + 결과 1행, detail = `sub`·`replayed`·`rejection`·종류/목표 상태·`reason=PRESENT|ABSENT`·`evidence=PRESENT|ABSENT`(원문 없음), 감사 실패 = 행위 실패.
 * - MASTER(TX 밖): 키 `mo1.<계정 ULID>.<submissionId>`, 상관 ID `mos.<submissionId>`, 본문 모든 key(선택 null), 담당자 `admin:adm_…`.
 */
class ContractIntakeCommandServiceSpec : BehaviorSpec({

    val operatorId = "adm_01JC00000000000000000000V1"
    val managerId = "adm_01JC00000000000000000000V2"
    val biz = "biz_01JC0000000000000000000001"
    val ctx = AdminRequestContext("req-ci", "127.0.0.1")
    var subSeq = 0
    fun sub() = "%032x".format(++subSeq)
    /** ADMIN 시계 = 2026-11-25T15:30Z = KST 2026-11-26 00:30(자정 직후 — 브라우저·UTC 날짜와 다른 KST 날짜). */
    val now = Instant.parse("2026-11-25T15:30:00Z")
    val todayKst = "2026-11-26"

    fun open(s: String = sub(), receivedOn: String = todayKst, channel: String = "PHONE", manager: String? = managerId, services: List<String> = listOf("CARD_AUTH"), reason: String? = null, kind: String = "NEW") =
        ContractIntakeRequest.Open(biz, s, kind, receivedOn, channel, manager, services, reason)
    /** FLOW-2d 접수 항목 수정(접수 건 #2, 기대 version 1). [manager] = 화면 참조를 웹 adapter가 푼 계정 키(풀리지 않으면 null). */
    fun items(s: String = sub(), receivedOn: String = todayKst, channel: String = "WEBSITE", manager: String? = managerId, services: List<String> = listOf("CARD_AUTH"), reason: String? = null) =
        ContractIntakeRequest.UpdateItems(biz, 2, s, 1, receivedOn, channel, manager, services, reason)
    fun move(target: String, reason: String? = null, s: String = sub()) = ContractIntakeRequest.Move(biz, 2, s, 1, target, reason)
    fun complete(s: String = sub(), documentRef: String = "DEMO-DOC-0001", start: String = todayKst, end: String? = null, services: List<String> = listOf("CARD_PG_PAYMENT"), reason: String? = null) =
        ContractIntakeRequest.Complete(biz, 2, s, 1, documentRef, start, end, services, reason)
    /** FLOW-2c 사업자 계약 정지·정지 해제·해지(계약 #1, 기대 사건 수 0). */
    fun event(op: ContractIntakeOperation, reason: String? = "거래 확인 필요", evidence: String? = null, s: String = sub(), seq: Int = 1, count: Int = 0) =
        ContractIntakeRequest.ContractEvent(op, biz, seq, s, count, reason, evidence)

    class Fixture(roles: Set<String> = setOf("OPERATOR"), status: String = "ACTIVE", var now: Instant, perMinute: Int = 100) {
        val audits = mutableListOf<AuditRecord>()
        val events = mutableListOf<String>()
        val commands = mutableListOf<MasterContractIntakeCommand>()
        val metrics = mutableListOf<String>()
        val callInTx = mutableListOf<Boolean>()
        val trailQueries = mutableListOf<String>()
        var failAuditAt: Int? = null
        var failTrail = false
        var reply: (MasterContractIntakeCommand) -> MasterBusinessCommandResult = { MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000001", null, false, null) }
        val views = mutableMapOf(
            "adm_01JC00000000000000000000V1" to AdminSessionView("adm_01JC00000000000000000000V1", "운영 담당", status, 1, 1, roles),
            "adm_01JC00000000000000000000V2" to AdminSessionView("adm_01JC00000000000000000000V2", "영업 담당", "ACTIVE", 1, 1, setOf("OPERATOR")),
            "adm_01JC00000000000000000000V3" to AdminSessionView("adm_01JC00000000000000000000V3", "정지 담당", "SUSPENDED", 1, 1, setOf("OPERATOR")),
            "adm_01JC00000000000000000000V4" to AdminSessionView("adm_01JC00000000000000000000V4", "승인 담당", "ACTIVE", 1, 1, setOf("APPROVER")),
        )
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val limits = object : MerchantOpsLimits {
            override val lookupPerMinute = 100
            override val registrationNumberSearchesPerMinute = 100
            override val registrationsPerMinute = perMinute
        }
        val gateway = object : MasterContractIntakeGateway {
            override fun contractIntakeCommand(command: MasterContractIntakeCommand): MasterBusinessCommandResult {
                callInTx += TransactionSynchronizationManager.isActualTransactionActive()
                events += "call"
                commands += command
                return reply(command)
            }
        }
        val trail = object : AdminAuditTrail {
            override fun requested(actorId: String, action: String, correlationId: String): Boolean {
                if (failTrail) throw IllegalStateException("injected trail failure")
                trailQueries += "$actorId|$action|$correlationId"
                return audits.any { it.actorId == actorId && it.action == action && it.correlationId == correlationId && it.resultCode == "REQUESTED" }
            }
        }
        val service = ContractIntakeCommandService(
            tx,
            object : AdminSessionQuery {
                override fun current(actorId: String) = views[actorId]
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    if (failAuditAt == audits.size) {
                        failAuditAt = null
                        events += "audit-failed"
                        throw IllegalStateException("injected audit failure")
                    }
                    audits += record
                    events += "audit:${record.resultCode}"
                }
            },
            trail,
            gateway,
            limits,
            object : MerchantOpsMetrics {
                override fun rateLimited(kind: String) {
                    metrics += "rate:$kind"
                }
                override fun command(operation: String, result: String) {
                    metrics += "command:$operation:$result"
                }
                override fun commandUnknown(operation: String) {
                    metrics += "unknown:$operation"
                }
            },
            object : BusinessOpsMetrics {
                override fun lookup(result: String) = Unit
                override fun ruleDrift(operation: String) {
                    metrics += "drift:$operation"
                }
            },
        ) { now }
        fun rows() = audits.map { "${it.action}|${it.resultCode}|${it.detail}|${it.targetRef}" }
    }

    fun invalid(vararg errors: Pair<String, String>) = BusinessProfileResult.InvalidInput(errors.map { BusinessFieldError(it.first, it.second) })

    Given("permissions (D-BIZ-006 ① 관례 — OPERATOR 1인 명령, APPROVER는 조회만)") {
        Then("the contract-intake permission constant is OPERATOR only and ⊆ VIEW") {
            BusinessPermissions.CONTRACT_INTAKE shouldBe setOf("OPERATOR")
            BusinessPermissions.VIEW.containsAll(BusinessPermissions.CONTRACT_INTAKE) shouldBe true
        }
        Then("APPROVER / VIEWER → 403 + one FORBIDDEN audit row per command (target = businessId); MASTER is not called") {
            listOf(setOf("APPROVER"), setOf("VIEWER")).forEach { roles ->
                val f = Fixture(roles = roles, now = now)
                f.service.execute(operatorId, open(), ctx) shouldBe BusinessProfileResult.Forbidden
                f.service.execute(operatorId, move("DOCUMENT_RECEIVED"), ctx) shouldBe BusinessProfileResult.Forbidden
                f.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.Forbidden
                withClue(roles) {
                    f.rows().map { it.split('|').let { c -> "${c[0]}|${c[1]}|${c[3]}" } } shouldBe listOf(
                        "BUSINESS_CONTRACT_INTAKE_OPEN|FORBIDDEN|$biz", "BUSINESS_CONTRACT_INTAKE_MOVE|FORBIDDEN|$biz", "BUSINESS_CONTRACT_INTAKE_COMPLETE|FORBIDDEN|$biz",
                    )
                    f.commands.shouldBeEmpty()
                }
            }
        }
        Then("inactive account or no business role → 403 without audit") {
            listOf(Fixture(status = "SUSPENDED", now = now), Fixture(roles = setOf("UNMASK"), now = now)).forEach { f ->
                f.service.execute(operatorId, open(), ctx) shouldBe BusinessProfileResult.Forbidden
                f.audits.shouldBeEmpty()
                f.commands.shouldBeEmpty()
            }
        }
    }

    Given("ADMIN first checks (400 + fieldErrors — no MASTER call, no audit row; metrics only)") {
        Then("§4A.3a '사유 및 비고': 201 code points TOO_LONG, edge space FORMAT, control char FORMAT, card-number pattern CARD_NUMBER_PATTERN — on every command") {
            val f = Fixture(now = now)
            listOf(
                "가".repeat(201) to "TOO_LONG", " 사유" to "FORMAT", "사유\n비고" to "FORMAT", "카드 4111 1111 1111 1111 확인" to "CARD_NUMBER_PATTERN", "" to "REQUIRED",
            ).forEach { (reason, code) ->
                withClue(code) {
                    f.service.execute(operatorId, open(reason = reason), ctx) shouldBe invalid("reason" to code)
                    f.service.execute(operatorId, move("REJECTED", reason = reason), ctx) shouldBe invalid("reason" to code)
                    f.service.execute(operatorId, complete(reason = reason), ctx) shouldBe invalid("reason" to code)
                }
            }
            f.service.execute(operatorId, open(reason = "가".repeat(200)), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.commands.size shouldBe 1
        }
        Then("§4A.3 revert (→ CONSULTING) and reject (→ REJECTED) need the reason; → DOCUMENT_RECEIVED does not") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, move("CONSULTING"), ctx) shouldBe invalid("reason" to "REQUIRED")
            f.service.execute(operatorId, move("REJECTED"), ctx) shouldBe invalid("reason" to "REQUIRED")
            f.commands.shouldBeEmpty()
            f.service.execute(operatorId, move("DOCUMENT_RECEIVED"), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.service.execute(operatorId, move("REJECTED", reason = "고객 사정으로 계약 불가"), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.commands.size shouldBe 2
        }
        Then("§4A.4 document ref: empty REQUIRED, 129 chars TOO_LONG, free text FORMAT, a card number CARD_NUMBER_PATTERN (no value in the error)") {
            val f = Fixture(now = now)
            listOf(
                "" to "REQUIRED", "A".repeat(129) to "TOO_LONG", "계약서 받음" to "FORMAT", "DOC 1" to "FORMAT", "DOC-4111111111111111" to "CARD_NUMBER_PATTERN",
                "DOC:4111-1111-1111-1111" to "CARD_NUMBER_PATTERN",
            ).forEach { (ref, code) -> withClue(ref) { f.service.execute(operatorId, complete(documentRef = ref), ctx) shouldBe invalid("documentRef" to code) } }
            f.service.execute(operatorId, complete(documentRef = "A".repeat(128)), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.service.execute(operatorId, complete(documentRef = "DEMO/DOC:2026#1_a.b-c"), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.commands.size shouldBe 2
            f.audits.count { it.resultCode == "INVALID_INPUT" } shouldBe 0
        }
        Then("n-4 received-on date: ADMIN clock in KST — tomorrow (KST) NOT_ALLOWED, today (KST, though UTC is still yesterday) and the past allowed; bad dates FORMAT / REQUIRED") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, open(receivedOn = "2026-11-27"), ctx) shouldBe invalid("receivedOn" to "NOT_ALLOWED")
            f.service.execute(operatorId, open(receivedOn = "2026-02-30"), ctx) shouldBe invalid("receivedOn" to "FORMAT")
            f.service.execute(operatorId, open(receivedOn = "20261126"), ctx) shouldBe invalid("receivedOn" to "FORMAT")
            f.service.execute(operatorId, open(receivedOn = ""), ctx) shouldBe invalid("receivedOn" to "REQUIRED")
            f.commands.shouldBeEmpty()
            f.service.execute(operatorId, open(receivedOn = todayKst), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.service.execute(operatorId, open(receivedOn = "2026-11-01"), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.commands.size shouldBe 2
        }
        Then("§4B.3 start / end dates: start yesterday (KST) NOT_ALLOWED, start today allowed, end before start NOT_ALLOWED, end = start allowed, bad end FORMAT") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, complete(start = "2026-11-25"), ctx) shouldBe invalid("startDate" to "NOT_ALLOWED")
            f.service.execute(operatorId, complete(start = "2026-12-01", end = "2026-11-30"), ctx) shouldBe invalid("endDate" to "NOT_ALLOWED")
            f.service.execute(operatorId, complete(end = "2026/12/31"), ctx) shouldBe invalid("endDate" to "FORMAT")
            f.service.execute(operatorId, complete(start = ""), ctx) shouldBe invalid("startDate" to "REQUIRED")
            f.commands.shouldBeEmpty()
            f.service.execute(operatorId, complete(start = todayKst), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.service.execute(operatorId, complete(start = "2026-12-01", end = "2026-12-01"), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
        }
        Then("scope: empty services REQUIRED, unknown service FORMAT (BR-BIZ-329 — only CARD_PG_PAYMENT; the desired services are a different field)") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, complete(services = emptyList()), ctx) shouldBe invalid("services" to "REQUIRED")
            f.service.execute(operatorId, complete(services = listOf("CARD_AUTH")), ctx) shouldBe invalid("services[0]" to "FORMAT")
            f.commands.shouldBeEmpty()
        }
        Then("§4A.6 items: channel SALES_AGENCY NOT_ALLOWED (FLOW-4 전), unknown FORMAT, empty REQUIRED; desired service VIRTUAL_ACCOUNT FORMAT; manager key format FORMAT; errors sorted by path") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, open(channel = "SALES_AGENCY"), ctx) shouldBe invalid("intakeChannel" to "NOT_ALLOWED")
            f.service.execute(operatorId, open(channel = "EMAIL"), ctx) shouldBe invalid("intakeChannel" to "FORMAT")
            f.service.execute(operatorId, open(channel = ""), ctx) shouldBe invalid("intakeChannel" to "REQUIRED")
            f.service.execute(operatorId, open(services = listOf("CARD_KEYIN", "VIRTUAL_ACCOUNT")), ctx) shouldBe invalid("desiredServices[1]" to "FORMAT")
            f.service.execute(operatorId, open(manager = "admin:adm_01JC00000000000000000000V2"), ctx) shouldBe invalid("contractManager" to "FORMAT")
            f.service.execute(operatorId, open(channel = "EMAIL", receivedOn = "x", reason = " r"), ctx) shouldBe
                invalid("intakeChannel" to "FORMAT", "reason" to "FORMAT", "receivedOn" to "FORMAT")
            f.service.execute(operatorId, open(services = emptyList()), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.commands.size shouldBe 1
        }
        Then("n-3 contract manager = ACTIVE OPERATOR account (ADMIN's own admin_account): suspended, APPROVER-only or unknown → contractManager NOT_ALLOWED; self allowed") {
            val f = Fixture(now = now)
            listOf("adm_01JC00000000000000000000V3", "adm_01JC00000000000000000000V4", "adm_01JC00000000000000000000V9").forEach { m ->
                withClue(m) { f.service.execute(operatorId, open(manager = m), ctx) shouldBe invalid("contractManager" to "NOT_ALLOWED") }
            }
            f.commands.shouldBeEmpty()
            f.audits.shouldBeEmpty()
            f.service.execute(operatorId, open(manager = operatorId), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.commands.single().body["contractManager"] shouldBe "admin:$operatorId"
        }
        Then("structural identifiers: a bad submissionId or businessId → 400 without field errors, no MASTER call") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, open(s = "not-hex"), ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.service.execute(operatorId, ContractIntakeRequest.Move("biz_x", 2, sub(), 1, "DOCUMENT_RECEIVED", null), ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.commands.shouldBeEmpty()
        }
    }

    Given("n-3 resend with the same submissionId: stored result, no re-check of time-dependent rules; the contract manager is always re-checked") {
        // 기대값 변경(FLOW-2b 리뷰 MINOR-1 — Coordinator 결정, 강화): 재전송이라도 담당자가 정지 계정이면 400 NOT_ALLOWED(이전: 재검사 없이 MASTER 재생). MASTER 재호출 없음.
        Then("first send passed (REQUESTED audited) → manager suspended afterwards → same submissionId resend is 400 contractManager NOT_ALLOWED (account check always runs)") {
            val f = Fixture(now = now)
            val s = sub()
            f.reply = { MasterBusinessCommandResult.Unknown("TIMEOUT") }
            f.service.execute(operatorId, open(s = s), ctx) shouldBe BusinessProfileResult.OutcomeUnknown
            f.views["adm_01JC00000000000000000000V2"] = AdminSessionView("adm_01JC00000000000000000000V2", "영업 담당", "SUSPENDED", 1, 1, setOf("OPERATOR"))
            f.service.execute(operatorId, open(s = s), ctx) shouldBe invalid("contractManager" to "NOT_ALLOWED")
            f.commands.map { it.idempotencyKey } shouldBe listOf("mo1.01JC00000000000000000000V1.$s")
        }
        Then("a completion first sent on its start day and resent after the KST midnight is not re-judged as retroactive (MASTER returns the stored result)") {
            val f = Fixture(now = Instant.parse("2026-11-26T14:59:00Z"))
            val s = sub()
            f.reply = { MasterBusinessCommandResult.RetrySameKey("OUTCOME_UNKNOWN") }
            f.service.execute(operatorId, complete(s = s, start = "2026-11-26"), ctx) shouldBe BusinessProfileResult.RetrySameKey
            f.now = Instant.parse("2026-11-26T15:01:00Z")
            f.reply = { MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000001", null, true, null) }
            f.service.execute(operatorId, complete(s = s, start = "2026-11-26"), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, true)
            f.commands.size shouldBe 2
        }
        Then("a new submissionId with the same content is re-checked (no stored result) → 400; a trail lookup failure → 503 AUDIT_UNAVAILABLE (fail-closed, MASTER not called)") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, open(), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.views["adm_01JC00000000000000000000V2"] = AdminSessionView("adm_01JC00000000000000000000V2", "영업 담당", "SUSPENDED", 1, 1, setOf("OPERATOR"))
            f.service.execute(operatorId, open(), ctx) shouldBe invalid("contractManager" to "NOT_ALLOWED")
            f.views["adm_01JC00000000000000000000V2"] = AdminSessionView("adm_01JC00000000000000000000V2", "영업 담당", "ACTIVE", 1, 1, setOf("OPERATOR"))
            f.failTrail = true
            // 입력 변경(MINOR-1): trail 조회는 시각 의존 검사 실패일 때만 일어난다 — 접수일 내일(KST)로 trail 조회를 일으킨다. 기대(503 fail-closed, MASTER 미호출)는 그대로.
            f.service.execute(operatorId, open(receivedOn = "2026-11-27"), ctx) shouldBe BusinessProfileResult.AuditUnavailable
            f.commands.size shouldBe 1
        }
        Then("format errors are never skipped (a resend of a malformed value is still 400 — the first send never reached MASTER)") {
            val f = Fixture(now = now)
            val s = sub()
            f.service.execute(operatorId, open(s = s, channel = "EMAIL"), ctx) shouldBe invalid("intakeChannel" to "FORMAT")
            f.service.execute(operatorId, open(s = s, channel = "EMAIL"), ctx) shouldBe invalid("intakeChannel" to "FORMAT")
            f.trailQueries.shouldBeEmpty()
        }
    }

    Given("the MASTER call and the audit rows (fail-closed)") {
        Then("open: REQUESTED → MASTER (outside a DB TX, signed body with every key) → OK; key/correlation per submissionId; detail has kind and reason/evidence presence only") {
            val f = Fixture(now = now)
            val s = sub()
            val reason = "전화 상담 메모 CANARY-OPEN-REASON"
            f.service.execute(operatorId, open(s = s, reason = reason, services = listOf("CARD_AUTH", "CARD_KEYIN"), kind = "CHANGE"), ctx) shouldBe
                BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.events shouldBe listOf("audit:REQUESTED", "call", "audit:OK")
            f.callInTx shouldBe listOf(false)
            val c = f.commands.single()
            listOf(c.operation, c.idempotencyKey, c.actor, c.correlationId) shouldBe
                listOf(ContractIntakeOperation.OPEN, "mo1.01JC00000000000000000000V1.$s", "admin:$operatorId", "mos.$s")
            c.body shouldBe linkedMapOf(
                "businessId" to biz, "expectedKind" to "CHANGE", "receivedOn" to todayKst, "intakeChannel" to "PHONE", "contractManager" to "admin:$managerId",
                "desiredServices" to listOf("CARD_AUTH", "CARD_KEYIN"), "reason" to reason,
            )
            c.body.keys.toList() shouldBe listOf("businessId", "expectedKind", "receivedOn", "intakeChannel", "contractManager", "desiredServices", "reason")
            f.rows() shouldBe listOf(
                "BUSINESS_CONTRACT_INTAKE_OPEN|REQUESTED|sub=${s.take(8)};kind=CHANGE;reason=PRESENT;evidence=ABSENT|$biz",
                "BUSINESS_CONTRACT_INTAKE_OPEN|OK|sub=${s.take(8)};replayed=false;kind=CHANGE;reason=PRESENT;evidence=ABSENT|$biz",
            )
            f.audits.map { it.correlationId } shouldBe listOf("mos.$s", "mos.$s")
            f.metrics shouldBe listOf("command:OpenContractIntake:OK")
        }
        Then("move and complete bodies: path intake seq, expected version, target / document ref, dates (end null kept), services, reason null kept; detail target and evidence presence") {
            val f = Fixture(now = now)
            val s1 = sub()
            f.service.execute(operatorId, move("REJECTED", reason = "고객 사정", s = s1), ctx)
            f.commands[0].body shouldBe linkedMapOf("businessId" to biz, "intakeSeq" to 2, "expectedVersion" to 1L, "target" to "REJECTED", "reason" to "고객 사정")
            val s2 = sub()
            f.service.execute(operatorId, complete(s = s2), ctx)
            f.commands[1].body shouldBe linkedMapOf(
                "businessId" to biz, "intakeSeq" to 2, "expectedVersion" to 1L, "documentRef" to "DEMO-DOC-0001", "startDate" to todayKst, "endDate" to null,
                "services" to listOf("CARD_PG_PAYMENT"), "reason" to null,
            )
            f.commands.map { it.operation } shouldBe listOf(ContractIntakeOperation.MOVE, ContractIntakeOperation.COMPLETE)
            f.rows() shouldBe listOf(
                "BUSINESS_CONTRACT_INTAKE_MOVE|REQUESTED|sub=${s1.take(8)};target=REJECTED;reason=PRESENT;evidence=ABSENT|$biz",
                "BUSINESS_CONTRACT_INTAKE_MOVE|OK|sub=${s1.take(8)};replayed=false;target=REJECTED;reason=PRESENT;evidence=ABSENT|$biz",
                "BUSINESS_CONTRACT_INTAKE_COMPLETE|REQUESTED|sub=${s2.take(8)};target=COMPLETED;reason=ABSENT;evidence=PRESENT|$biz",
                "BUSINESS_CONTRACT_INTAKE_COMPLETE|OK|sub=${s2.take(8)};replayed=false;target=COMPLETED;reason=ABSENT;evidence=PRESENT|$biz",
            )
        }
        Then("stored rejection → 200 REJECTED (audit REJECTED with the code); MASTER 400 → 400 fieldErrors + INVALID_INPUT audit + drift; 409 → KEY_REUSED; unknown → OUTCOME_UNKNOWN + metric") {
            val f = Fixture(now = now)
            f.reply = { MasterBusinessCommandResult.Decided(false, null, "BUSINESS_CONTRACT_START_RETROACTIVE", true, null) }
            f.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.Decided("REJECTED", null, "BUSINESS_CONTRACT_START_RETROACTIVE", true)
            f.reply = { MasterBusinessCommandResult.Invalid(listOf(BusinessFieldError("documentRef", "FORMAT"))) }
            f.service.execute(operatorId, complete(), ctx) shouldBe invalid("documentRef" to "FORMAT")
            f.reply = { MasterBusinessCommandResult.KeyReused }
            f.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.KeyReused
            f.reply = { MasterBusinessCommandResult.Unknown("TIMEOUT") }
            f.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.OutcomeUnknown
            f.reply = { MasterBusinessCommandResult.RetrySameKey("REQUEST_IN_PROGRESS") }
            f.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.RetrySameKey
            f.reply = { MasterBusinessCommandResult.Busy("ADMIN_WRITE_BUSY") }
            f.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.MasterBusy
            f.rows().filter { "|REQUESTED|" !in it }.map { row ->
                row.split('|').let { c -> c[1] + "|" + c[2].split(';').filter { k -> k.startsWith("replayed") || k.startsWith("rejection") }.joinToString(";") }
            } shouldBe listOf(
                "REJECTED|replayed=true;rejection=BUSINESS_CONTRACT_START_RETROACTIVE", "INVALID_INPUT|", "KEY_REUSED|", "OUTCOME_UNKNOWN|", "OUTCOME_UNKNOWN|", "BUSY|",
            )
            f.metrics.filter { it.startsWith("drift") || it.startsWith("unknown") } shouldBe
                listOf("drift:CompleteContractIntake", "unknown:CompleteContractIntake", "unknown:CompleteContractIntake")
        }
        Then("a success whose reference is not the requested business → OUTCOME_UNKNOWN (REFERENCE_MISMATCH — same submissionId resend confirms)") {
            val f = Fixture(now = now)
            f.reply = { MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000099", null, false, null) }
            f.service.execute(operatorId, move("DOCUMENT_RECEIVED"), ctx) shouldBe BusinessProfileResult.OutcomeUnknown
            f.rows().last().split('|')[1] shouldBe "OUTCOME_UNKNOWN"
        }
        Then("audit failure on the attempt row → AUDIT_UNAVAILABLE, MASTER not called; on the result row → AUDIT_UNAVAILABLE after the call (resend recovers)") {
            val first = Fixture(now = now).apply { failAuditAt = 0 }
            first.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.AuditUnavailable
            first.commands.shouldBeEmpty()
            val second = Fixture(now = now).apply { failAuditAt = 1 }
            second.service.execute(operatorId, complete(), ctx) shouldBe BusinessProfileResult.AuditUnavailable
            second.events shouldBe listOf("audit:REQUESTED", "call", "audit-failed")
            val forbidden = Fixture(roles = setOf("APPROVER"), now = now).apply { failAuditAt = 0 }
            forbidden.service.execute(operatorId, open(), ctx) shouldBe BusinessProfileResult.AuditUnavailable
        }
        Then("rate window (registrations-per-minute, Demo) → 429 + RATE_LIMITED audit, MASTER not called") {
            val f = Fixture(now = now, perMinute = 1)
            f.service.execute(operatorId, move("DOCUMENT_RECEIVED"), ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.service.execute(operatorId, move("DOCUMENT_RECEIVED"), ctx) shouldBe BusinessProfileResult.RateLimited
            f.commands.size shouldBe 1
            f.rows().last().split('|').take(2) shouldBe listOf("BUSINESS_CONTRACT_INTAKE_MOVE", "RATE_LIMITED")
            f.metrics shouldBe listOf("command:MoveContractIntake:OK", "rate:CONTRACT_INTAKE", "command:MoveContractIntake:RATE_LIMITED")
        }
        Then("no raw reason / document ref / manager key in audit rows, metrics, logs or request toString (BIZ-3-6 canary)") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            val f = Fixture(now = now)
            try {
                f.service.execute(operatorId, complete(documentRef = "CANARY-DOC-77", reason = "CANARY 사유 77"), ctx)
                f.service.execute(operatorId, open(reason = "CANARY 사유 78"), ctx)
                f.reply = { MasterBusinessCommandResult.Unknown("TIMEOUT") }
                f.service.execute(operatorId, move("REJECTED", reason = "CANARY 사유 79"), ctx)
            } finally {
                root.detachAppender(appender)
            }
            val texts = f.audits.map { it.detail.orEmpty() + it.toString() } + f.metrics + appender.list.map { it.formattedMessage } +
                listOf(complete(documentRef = "CANARY-DOC-77", reason = "CANARY 사유 77").toString(), f.commands.last().toString())
            texts.forEach { t ->
                listOf("CANARY", managerId).forEach { needle -> withClue(t) { t shouldNotContain needle } }
            }
        }
    }

    Given("rejectMalformed (body did not parse strictly)") {
        Then("OPERATOR → 400 without field errors and no audit (metrics only); APPROVER → 403 + FORBIDDEN audit; target only when the path id is well-formed") {
            val f = Fixture(now = now)
            f.service.rejectMalformed(operatorId, ContractIntakeOperation.OPEN, biz, ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.audits.shouldBeEmpty()
            val a = Fixture(roles = setOf("APPROVER"), now = now)
            a.service.rejectMalformed(operatorId, ContractIntakeOperation.COMPLETE, biz, ctx) shouldBe BusinessProfileResult.Forbidden
            a.service.rejectMalformed(operatorId, ContractIntakeOperation.MOVE, "biz_bad", ctx) shouldBe BusinessProfileResult.Forbidden
            a.rows() shouldBe listOf("BUSINESS_CONTRACT_INTAKE_COMPLETE|FORBIDDEN|null|$biz", "BUSINESS_CONTRACT_INTAKE_MOVE|FORBIDDEN|null|null")
        }
    }

    Given("FLOW-2c business contract commands (suspend · resume · terminate — D-GOV-022: reason required, evidence optional)") {
        val ops = listOf(ContractIntakeOperation.SUSPEND, ContractIntakeOperation.RESUME, ContractIntakeOperation.TERMINATE)

        Then("OPERATOR → REQUESTED → MASTER (business-contracts path, every key, reason / evidence passed) → OK; audit detail kind + presence only") {
            ops.forEach { op ->
                val f = Fixture(now = now)
                val s = sub()
                f.service.execute(operatorId, event(op, evidence = if (op == ContractIntakeOperation.RESUME) null else "CASE/2026-11:77", s = s), ctx) shouldBe
                    BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
                val c = f.commands.single()
                listOf(c.operation, c.idempotencyKey, c.correlationId, c.body.keys.toList()) shouldBe
                    listOf(op, "mo1.01JC00000000000000000000V1.$s", "mos.$s", listOf("businessId", "contractSeq", "expectedEventCount", "reason", "evidenceRef"))
                listOf(c.body["contractSeq"], c.body["expectedEventCount"], c.body["reason"]) shouldBe listOf(1, 0, "거래 확인 필요")
                val evidence = if (op == ContractIntakeOperation.RESUME) "ABSENT" else "PRESENT"
                f.rows() shouldBe listOf(
                    "${op.auditAction}|REQUESTED|sub=${s.take(8)};kind=${op.name};reason=PRESENT;evidence=$evidence|$biz",
                    "${op.auditAction}|OK|sub=${s.take(8)};replayed=false;kind=${op.name};reason=PRESENT;evidence=$evidence|$biz",
                )
                f.rows().joinToString().contains("거래 확인 필요") shouldBe false
                f.rows().joinToString().contains("CASE/2026-11:77") shouldBe false
            }
            ops.map { it.auditAction } shouldBe listOf("BUSINESS_CONTRACT_SUSPEND", "BUSINESS_CONTRACT_RESUME", "BUSINESS_CONTRACT_TERMINATE")
        }

        Then("reason required (REQUIRED / TOO_LONG / CARD_NUMBER_PATTERN), evidence format and card pattern → 400 without MASTER call; bad contractSeq / count → 400 without fields") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, event(ContractIntakeOperation.TERMINATE, reason = null), ctx) shouldBe invalid("reason" to "REQUIRED")
            f.service.execute(operatorId, event(ContractIntakeOperation.SUSPEND, reason = "가".repeat(201)), ctx) shouldBe invalid("reason" to "TOO_LONG")
            f.service.execute(operatorId, event(ContractIntakeOperation.SUSPEND, reason = "card 4111 1111 1111 1111"), ctx) shouldBe invalid("reason" to "CARD_NUMBER_PATTERN")
            f.service.execute(operatorId, event(ContractIntakeOperation.RESUME, evidence = "bad ref"), ctx) shouldBe invalid("evidenceRef" to "FORMAT")
            f.service.execute(operatorId, event(ContractIntakeOperation.RESUME, evidence = "4111-1111-1111-1111"), ctx) shouldBe invalid("evidenceRef" to "CARD_NUMBER_PATTERN")
            f.service.execute(operatorId, event(ContractIntakeOperation.TERMINATE, seq = 0), ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.service.execute(operatorId, event(ContractIntakeOperation.TERMINATE, count = -1), ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.commands.size shouldBe 0
        }

        Then("APPROVER → 403 + FORBIDDEN audit; a stored MASTER rejection → 200 REJECTED with the code in the audit") {
            Fixture(roles = setOf("APPROVER"), now = now).let { f ->
                f.service.execute(operatorId, event(ContractIntakeOperation.TERMINATE), ctx) shouldBe BusinessProfileResult.Forbidden
                f.rows().map { it.split('|').take(2) } shouldBe listOf(listOf("BUSINESS_CONTRACT_TERMINATE", "FORBIDDEN"))
            }
            val f = Fixture(now = now)
            f.reply = { MasterBusinessCommandResult.Decided(false, null, "BUSINESS_CONTRACT_STATE_CONFLICT", false, null) }
            f.service.execute(operatorId, event(ContractIntakeOperation.TERMINATE), ctx) shouldBe
                BusinessProfileResult.Decided("REJECTED", null, "BUSINESS_CONTRACT_STATE_CONFLICT", false)
            f.rows().last().split('|')[2].contains("rejection=BUSINESS_CONTRACT_STATE_CONFLICT") shouldBe true
        }
    }

    Given("FLOW-2d 접수 항목 수정(§4A.3 — D-FLOW-007 ④·D-FLOW-009 ①) and the contract-manager reference (D-GOV-024)") {
        Then("OPERATOR: REQUESTED → MASTER update-items (outside a DB TX, every key, manager admin:adm_…) → OK; audit detail target=ITEMS + presence only") {
            val f = Fixture(now = now)
            val s = sub()
            f.service.execute(operatorId, items(s = s, reason = "CANARY-ITEMS-REASON", services = listOf("CARD_AUTH", "CARD_KEYIN")), ctx) shouldBe
                BusinessProfileResult.Decided("SUCCEEDED", biz, null, false)
            f.events shouldBe listOf("audit:REQUESTED", "call", "audit:OK")
            f.callInTx shouldBe listOf(false)
            val c = f.commands.single()
            listOf(c.operation, c.operation.masterPath, c.idempotencyKey, c.correlationId) shouldBe
                listOf(ContractIntakeOperation.UPDATE_ITEMS, "update-items", "mo1.01JC00000000000000000000V1.$s", "mos.$s")
            c.body shouldBe linkedMapOf(
                "businessId" to biz, "intakeSeq" to 2, "expectedVersion" to 1L, "receivedOn" to todayKst, "intakeChannel" to "WEBSITE", "contractManager" to "admin:$managerId",
                "desiredServices" to listOf("CARD_AUTH", "CARD_KEYIN"), "reason" to "CANARY-ITEMS-REASON",
            )
            f.rows() shouldBe listOf(
                "BUSINESS_CONTRACT_INTAKE_ITEMS|REQUESTED|sub=${s.take(8)};target=ITEMS;reason=PRESENT;evidence=ABSENT|$biz",
                "BUSINESS_CONTRACT_INTAKE_ITEMS|OK|sub=${s.take(8)};replayed=false;target=ITEMS;reason=PRESENT;evidence=ABSENT|$biz",
            )
            f.audits.forEach { a -> listOf(a.detail, a.toString()).forEach { (it ?: "") shouldNotContain "CANARY"; (it ?: "") shouldNotContain managerId } }
            f.metrics shouldBe listOf("command:UpdateContractIntakeItems:OK")
        }

        Then("an unresolved manager reference (null — unknown, expired or another session's) → contractManager NOT_ALLOWED on open and update, no audit, MASTER not called") {
            val f = Fixture(now = now)
            f.service.execute(operatorId, open(manager = null), ctx) shouldBe invalid("contractManager" to "NOT_ALLOWED")
            f.service.execute(operatorId, items(manager = null), ctx) shouldBe invalid("contractManager" to "NOT_ALLOWED")
            f.commands.shouldBeEmpty()
            f.audits.shouldBeEmpty()
        }

        Then("the manager is always re-checked (suspended / APPROVER-only → NOT_ALLOWED, also on a same-submissionId resend); a future received date NOT_ALLOWED; card number in the reason; APPROVER → 403 + FORBIDDEN") {
            val f = Fixture(now = now)
            listOf("adm_01JC00000000000000000000V3", "adm_01JC00000000000000000000V4").forEach { m ->
                withClue(m) { f.service.execute(operatorId, items(manager = m), ctx) shouldBe invalid("contractManager" to "NOT_ALLOWED") }
            }
            f.service.execute(operatorId, items(receivedOn = "2026-11-27"), ctx) shouldBe invalid("receivedOn" to "NOT_ALLOWED")
            f.service.execute(operatorId, items(reason = "카드 4111 1111 1111 1111"), ctx) shouldBe invalid("reason" to "CARD_NUMBER_PATTERN")
            f.service.execute(operatorId, items(channel = "SALES_AGENCY"), ctx) shouldBe invalid("intakeChannel" to "NOT_ALLOWED")
            val s = sub()
            f.reply = { MasterBusinessCommandResult.Unknown("TIMEOUT") }
            f.service.execute(operatorId, items(s = s), ctx) shouldBe BusinessProfileResult.OutcomeUnknown
            f.views["adm_01JC00000000000000000000V2"] = AdminSessionView("adm_01JC00000000000000000000V2", "영업 담당", "SUSPENDED", 1, 1, setOf("OPERATOR"))
            f.service.execute(operatorId, items(s = s), ctx) shouldBe invalid("contractManager" to "NOT_ALLOWED")
            f.commands.size shouldBe 1
            val approver = Fixture(roles = setOf("APPROVER"), now = now)
            approver.service.execute(operatorId, items(), ctx) shouldBe BusinessProfileResult.Forbidden
            approver.rows() shouldBe listOf("BUSINESS_CONTRACT_INTAKE_ITEMS|FORBIDDEN|sub=${"%032x".format(subSeq).take(8)};target=ITEMS;reason=ABSENT;evidence=ABSENT|$biz")
        }
    }
})
