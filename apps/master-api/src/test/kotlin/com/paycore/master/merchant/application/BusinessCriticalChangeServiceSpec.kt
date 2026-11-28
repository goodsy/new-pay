package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.errorCodeOfUpdate
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows
import com.paycore.master.merchant.application.BusinessCriticalChangeCommandFixture.OTHER_OPERATOR
import com.paycore.master.merchant.application.BusinessCriticalChangeCommandFixture.REQUESTER
import com.paycore.master.merchant.application.BusinessCriticalChangeCommandFixture.apply
import com.paycore.master.merchant.application.BusinessCriticalChangeCommandFixture.propose
import com.paycore.master.merchant.application.OnboardingFixture.key
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.BusinessCriticalChangeQuery
import com.paycore.master.merchant.application.provided.BusinessCriticalChangeUseCase
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CompleteBusinessRegistrationCommand
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContent
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.CanonicalJson
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.CorporateRegistrationNumber
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.PhoneNumber
import com.paycore.master.merchant.domain.SeoulMidnight
import com.paycore.master.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.HexFormat

/**
 * BIZ-CRIT-APPROVAL-001 S2 MASTER 통합 시험(실제 MySQL 8.4, runtime 계정 — 명세 §3.3·§3.5·§3.6·§3.8·§6·§7.2 BusinessCriticalChangeServiceSpec):
 * 제안(사업자·하위 행·version·이력·Outbox 불변, `h1:` digest = MASTER 키 HMAC, 멱등 재생·409, 저장된 거절), 계약·가맹점 이력 판정(잠금 뒤 — 접수 건 제외, 가맹점만 있는 DRAFT 포함),
 * 근거 실행(diff·이력(approvalId·proposalId·요청자·승인자·대조 확인)·Outbox·근거 EXECUTED 같은 TX, 요청자 불일치·version·digest·차단 칸 저장 거절, 인원 재판정 없음, rollback),
 * 1인 경로 전환(2인 칸 `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`, 선택 근거 원문은 `business_change_evidence`·이력은 PRESENT/ABSENT), 권한 1142, 조회(사업자 범위).
 * 기대값은 명세 리터럴. 공유 DB라 시험마다 새 사업자·키.
 */
@MasterDbTest
class BusinessCriticalChangeServiceSpec(
    private val changes: BusinessCriticalChangeUseCase,
    private val query: BusinessCriticalChangeQuery,
    private val businesses: BusinessProfileCommandUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val intakes: ContractIntakeCommandUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val digester: RequestFingerprintDigester,
    private val registry: io.micrometer.core.instrument.MeterRegistry,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
    }

    val json = JsonMapper.builder().build()

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }

    val corp = BusinessFixtures.corporationProfile()
    fun phone(p: BusinessProfile, value: String = "0299990000") = p.copy(registration = p.registration.copy(phoneNumber = PhoneNumber(value)))
    fun corpNumber(p: BusinessProfile) = p.copy(registration = p.registration.copy(corporateRegistrationNumber = CorporateRegistrationNumber("1101110000002")))

    fun created(status: BusinessRegistrationStatus = BusinessRegistrationStatus.REGISTERED, name: String = "법인 상호"): String = succeeded(
        businesses.create(CreateBusinessCommand(request(), status, BusinessType.CORPORATION, BusinessFixtures.registrationNumber(), DisplayName(name), corp)),
    )

    fun version(id: String) = count("SELECT version FROM business WHERE business_id = ?", id)
    fun displayName(id: String) = text("SELECT display_name FROM business WHERE business_id = ?", id)
    fun proposals(id: String) = count("SELECT COUNT(*) FROM business_change_proposal WHERE business_id = ?", id)
    fun historyRows(id: String) = count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ?", id)
    fun outboxRows(id: String) = count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", id)
    fun lastHistory(id: String): Pair<String, Map<*, *>> = runtime(container).use { c ->
        c.prepareStatement("SELECT change_type, detail FROM master_change_history WHERE aggregate_id = ? ORDER BY id DESC LIMIT 1").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) to json.readValue(rs.getString(2), Map::class.java) }
        }
    }

    /** 사업자 한 건의 사업자 쪽 흔적(business·하위 행·이력·Outbox). */
    fun footprint(id: String): List<Any?> = listOf(
        version(id), displayName(id),
        count("SELECT COUNT(*) FROM business_representative WHERE business_id = ?", id),
        count("SELECT COUNT(*) FROM business_contact WHERE business_id = ?", id),
        text("SELECT phone_number FROM business_registration WHERE business_id = ?", id),
        historyRows(id), outboxRows(id),
    )

    /** 기대 digest: 같은 내용의 Domain 제안 digest 입력을 MASTER 키로 HMAC(명세 §3.2 — `h1:` + 소문자 hex). */
    fun expectedDigest(businessId: String, baseVersion: Long, type: BusinessType, name: String, profile: BusinessProfile): String {
        val input = CanonicalJson.write(
            mapOf("baseVersion" to baseVersion, "businessId" to businessId, "content" to json.readValue(contentJson(BusinessContent(type, DisplayName(name), profile.canonical())), Map::class.java)),
        )
        return "h1:" + HexFormat.of().formatHex(digester.digest(input.toByteArray(Charsets.UTF_8)))
    }

    fun proposalOf(result: AdminCommandResult): String = succeeded(result).also { it shouldMatch Regex("^bcp_[0-9A-HJKMNP-TV-Z]{26}$") }
    fun digestOf(proposalId: String) = text("SELECT content_digest FROM business_change_proposal WHERE proposal_id = ?", proposalId)!!
    fun rejected(result: AdminCommandResult): String = result.shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection.name

    /** 접수 건 하나만 연다(계약 이력 아님 — BR-BIZ-386). */
    fun intakeOnly(id: String) {
        val today = clock.now().atZone(SeoulMidnight.ZONE).toLocalDate()
        succeeded(
            intakes.open(OpenContractIntakeCommand(request(), BusinessId(id), ContractIntakeKind.NEW,
                ContractIntakeItems(today, ContractIntakeChannel.PHONE, OnboardingFixture.operator, setOf(DesiredService.CARD_AUTH)), null)),
        )
    }

    Given("§3.3 propose — a recording command that leaves the business untouched") {
        Then("REGISTERED name + phone change → SUCCEEDED bcp_…; business, child rows, version, history and Outbox unchanged; the proposal row carries base version, requester, reason, evidence and the keyed digest") {
            val id = created()
            val before = footprint(id)
            val k = key()
            val command = propose(id, 0, "새 법인 상호", phone(corp), key = k)
            val pid = proposalOf(changes.propose(command))
            footprint(id) shouldBe before
            proposals(id) shouldBe 1
            listOf(
                text("SELECT base_version FROM business_change_proposal WHERE proposal_id = ?", pid),
                text("SELECT requested_by FROM business_change_proposal WHERE proposal_id = ?", pid),
                text("SELECT reason FROM business_change_proposal WHERE proposal_id = ?", pid),
                text("SELECT evidence_ref FROM business_change_proposal WHERE proposal_id = ?", pid),
                text("SELECT subject_correction FROM business_change_proposal WHERE proposal_id = ?", pid),
            ) shouldBe listOf("0", REQUESTER.value, BusinessCriticalChangeCommandFixture.REASON, BusinessCriticalChangeCommandFixture.EVIDENCE, "false")
            digestOf(pid) shouldBe expectedDigest(id, 0, BusinessType.CORPORATION, "새 법인 상호", phone(corp))
            // 제안 표의 변경 항목은 마스킹 값(전화번호 원문 없음). 원문은 content 열에만.
            text("SELECT CAST(changes AS CHAR) FROM business_change_proposal WHERE proposal_id = ?", pid)!! shouldNotContain "0299990000"
            // 같은 키·같은 내용 = 재생(같은 제안), 다른 내용 = 409.
            changes.propose(command) shouldBe AdminCommandResult.Succeeded(pid, replayed = true)
            changes.propose(propose(id, 0, "다른 상호", corp, key = k)) shouldBe AdminCommandResult.IdempotencyKeyReused
            proposals(id) shouldBe 1
        }

        Then("1-person cells and blocked cells are stored rejections without a proposal row (DRAFT without history → NOT_REQUIRED; contract history + corporate number → SUBJECT_CHANGE_NOT_ALLOWED)") {
            val draft = created(BusinessRegistrationStatus.DRAFT)
            val k = key()
            rejected(changes.propose(propose(draft, 0, "바뀐 상호", corp, key = k))) shouldBe "BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED"
            text("SELECT result_code FROM admin_request WHERE idempotency_key = ?", k.value) shouldBe "BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED"
            val contracted = created()
            OnboardingFixture.contracted(onboarding, BusinessId(contracted))
            rejected(changes.propose(propose(contracted, 0, "법인 상호", corpNumber(corp)))) shouldBe "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"
            listOf(proposals(draft), proposals(contracted)) shouldBe listOf(0L, 0L)
            // 제안 판정 순서: version 충돌·사유 없음도 저장된 거절.
            rejected(changes.propose(propose(contracted, 9, "새 상호", corp))) shouldBe "BUSINESS_VERSION_CONFLICT"
            rejected(changes.propose(propose(contracted, 0, "새 상호", corp, reason = null))) shouldBe "BUSINESS_CHANGE_REASON_REQUIRED"
            rejected(changes.propose(propose(BusinessFixtures.individualRegistered().business.id.value, 0, "x", corp))) shouldBe "BUSINESS_NOT_FOUND"
        }

        Then("history is judged from rows after the business lock: an intake alone is no history; a migrated merchant alone on a DRAFT is history (BR-BIZ-386·394)") {
            val intake = created(BusinessRegistrationStatus.DRAFT)
            intakeOnly(intake)
            rejected(changes.propose(propose(intake, 0, "바뀐 상호", corp))) shouldBe "BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED"
            val migrated = created(BusinessRegistrationStatus.DRAFT)
            runtime(container).use { MasterSchemaRows.merchant(it, migrated) }
            proposalOf(changes.propose(propose(migrated, 0, "바뀐 상호", corp)))
            // 1인 경로도 같은 판정(가맹점만 있는 DRAFT의 상호 변경 = 2인).
            rejected(businesses.update(UpdateBusinessCommand(request(), BusinessId(migrated), 0, BusinessChangeReason("변경"), BusinessType.CORPORATION, DisplayName("바뀐 상호"), corp))) shouldBe
                "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"
        }
    }

    Given("§3.3 C apply with evidence") {
        Then("success: diff, version +1, history with approvalId·proposalId·requester·approver·revalidation, Outbox and an EXECUTED evidence row in one TX; number and contract/merchant rows untouched") {
            val id = created()
            val number = text("SELECT registration_number FROM business WHERE business_id = ?", id)
            val pid = proposalOf(changes.propose(propose(id, 0, "새 법인 상호", phone(corp))))
            val command = apply(id, 0, pid, digestOf(pid))
            val contractsBefore = count("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", id)
            // MINOR-1: 권리·MID 쪽 행 수(전체) — 사업자 정보 변경은 MID·권리 Snapshot을 만들지 않는다(P-12, BR-BIZ-021).
            fun downstream() = listOf(count("SELECT COUNT(*) FROM merchant_account"), count("SELECT COUNT(*) FROM entitlement_snapshot_issue"))
            val downstreamBefore = downstream()
            changes.applyWithEvidence(command) shouldBe AdminCommandResult.Succeeded(id, replayed = false)
            downstream() shouldBe downstreamBefore
            listOf(version(id), displayName(id), text("SELECT phone_number FROM business_registration WHERE business_id = ?", id)) shouldBe listOf(1L, "새 법인 상호", "0299990000")
            text("SELECT registration_number FROM business WHERE business_id = ?", id) shouldBe number
            count("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", id) shouldBe contractsBefore
            count("SELECT COUNT(*) FROM merchant WHERE business_id = ?", id) shouldBe 0
            val (type, detail) = lastHistory(id)
            type shouldBe "BusinessProfileUpdated"
            val e = command.evidence
            listOf(detail["approvalId"], detail["proposalId"], detail["requestedBy"], detail["approvedBy"], detail["revalidatedBy"], detail["revalidatedAt"], detail["reason"]) shouldBe
                listOf(e.approvalId, pid, REQUESTER.value, e.approvedBy.value, e.approvedBy.value, CanonicalJson.timestamp(e.approvedAt), BusinessCriticalChangeCommandFixture.REASON)
            (detail["changes"] as List<*>).map { (it as Map<*, *>)["field"] } shouldBe listOf("displayName", "registration.phoneNumber")
            text("SELECT actor_ref FROM master_change_history WHERE aggregate_id = ? ORDER BY id DESC LIMIT 1", id) shouldBe e.approvedBy.value
            text("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY id DESC LIMIT 1", id) shouldBe "BusinessProfileUpdated"
            listOf(
                text("SELECT operation FROM admin_approval_evidence WHERE approval_id = ?", e.approvalId),
                text("SELECT outcome FROM admin_approval_evidence WHERE approval_id = ?", e.approvalId),
            ) shouldBe listOf("ApplyBusinessCriticalChange", "EXECUTED")
            // 재전송 = 재생, 같은 approvalId·다른 본문 = 409.
            changes.applyWithEvidence(command) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
            changes.applyWithEvidence(apply(id, 1, pid, digestOf(pid), evidence = e)) shouldBe AdminCommandResult.IdempotencyKeyReused
            version(id) shouldBe 1
        }

        // §7.1 이동(BusinessProfileServiceSpec TC-13 :300-): 사유 있는 대표자 변경 성공 + 이력 사유·마스킹 대표자 ended/added 단언은 근거 실행 경로로 옮겼다(+ approvalId·proposalId).
        Then("moved TC-13: an approved representative change records the reason and masked representatives ended/added, plus approvalId and proposalId") {
            val id = created()
            val after = corp.copy(representatives = listOf(BusinessFixtures.joint("이공동"), BusinessFixtures.joint("나공동")))
            val pid = proposalOf(changes.propose(propose(id, 0, "법인 상호", after, reason = "공동대표 교체")))
            val command = apply(id, 0, pid, digestOf(pid))
            succeeded(changes.applyWithEvidence(command)) shouldBe id
            val detail = lastHistory(id).second
            listOf(detail["reason"], detail["approvalId"], detail["proposalId"]) shouldBe listOf("공동대표 교체", command.evidence.approvalId, pid)
            ((detail["changes"] as List<*>).single() as Map<*, *>) shouldBe mapOf(
                "field" to "representatives",
                "ended" to listOf(mapOf("name" to "박**", "type" to "JOINT", "order" to 2)),
                "added" to listOf(mapOf("name" to "나**", "type" to "JOINT", "order" to 2)),
            )
            // MINOR-3: 갱신 행위자 = 승인자(DB) — 종료된 대표자 행의 ended_by, 사업자 행의 updated_by.
            listOf(
                text("SELECT ended_by FROM business_representative WHERE business_id = ? AND effective_to IS NOT NULL", id),
                text("SELECT updated_by FROM business WHERE business_id = ?", id),
            ) shouldBe listOf(command.evidence.approvedBy.value, command.evidence.approvedBy.value)
        }

        Then("M-6 proposal requester ≠ evidence requester → stored PROPOSAL_REQUESTER_MISMATCH with a REJECTED evidence row; the business stays") {
            val id = created()
            val pid = proposalOf(changes.propose(propose(id, 0, "새 법인 상호", corp, actor = OTHER_OPERATOR)))
            val command = apply(id, 0, pid, digestOf(pid), requestedBy = REQUESTER)
            rejected(changes.applyWithEvidence(command)) shouldBe "PROPOSAL_REQUESTER_MISMATCH"
            listOf(version(id), displayName(id)) shouldBe listOf(0L, "법인 상호")
            listOf(
                text("SELECT outcome FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId),
                text("SELECT result_code FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId),
            ) shouldBe listOf("REJECTED", "PROPOSAL_REQUESTER_MISMATCH")
        }

        Then("version moved by a 1-person general update → BUSINESS_VERSION_CONFLICT; another digest or an unknown proposal → PROPOSAL_CONTENT_MISMATCH") {
            val id = created()
            val pid = proposalOf(changes.propose(propose(id, 0, "새 법인 상호", corp)))
            rejected(changes.applyWithEvidence(apply(id, 0, pid, "h1:" + "a".repeat(64)))) shouldBe "PROPOSAL_CONTENT_MISMATCH"
            rejected(changes.applyWithEvidence(apply(id, 0, "bcp_" + com.paycore.master.merchant.domain.MasterFixture.ulid(), digestOf(pid)))) shouldBe "PROPOSAL_CONTENT_MISMATCH"
            succeeded(businesses.update(UpdateBusinessCommand(request(), BusinessId(id), 0, null, BusinessType.CORPORATION, DisplayName("법인 상호"), phone(corp, "0288887777"))))
            rejected(changes.applyWithEvidence(apply(id, 0, pid, digestOf(pid)))) shouldBe "BUSINESS_VERSION_CONFLICT"
            displayName(id) shouldBe "법인 상호"
        }

        Then("R2-m1 a pending name change still executes after a contract appears; a pending corporate-number correction becomes blocked (BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED)") {
            val named = created()
            val namePid = proposalOf(changes.propose(propose(named, 0, "새 법인 상호", corp)))
            val corrected = created()
            val correctionPid = proposalOf(changes.propose(propose(corrected, 0, "법인 상호", corpNumber(corp))))
            text("SELECT subject_correction FROM business_change_proposal WHERE proposal_id = ?", correctionPid) shouldBe "true"
            OnboardingFixture.contracted(onboarding, BusinessId(named))
            OnboardingFixture.contracted(onboarding, BusinessId(corrected))
            succeeded(changes.applyWithEvidence(apply(named, 0, namePid, digestOf(namePid)))) shouldBe named
            rejected(changes.applyWithEvidence(apply(corrected, 0, correctionPid, digestOf(correctionPid)))) shouldBe "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"
            text("SELECT corporate_registration_number FROM business_registration WHERE business_id = ?", corrected) shouldBe "1101110000001"
        }

        Then("rollback: an Outbox failure after the diff leaves no business change, no history and no evidence row") {
            val id = created()
            val pid = proposalOf(changes.propose(propose(id, 0, "새 법인 상호", corp)))
            val before = footprint(id)
            val command = apply(id, 0, pid, digestOf(pid))
            faults.after(MasterFaultPoint.OUTBOX_APPEND) { throw IllegalStateException("injected outbox failure") }
            shouldThrow<IllegalStateException> { changes.applyWithEvidence(command) }
            faults.reset()
            footprint(id) shouldBe before
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId) shouldBe 0
            // 같은 명령 재전송은 처음 실행으로 성공한다(효과 없음 확정 뒤 재시도).
            changes.applyWithEvidence(command) shouldBe AdminCommandResult.Succeeded(id, replayed = false)
        }
    }

    Given("MINOR-2 an integrity defect during execution (recomputed change ≠ the stored proposal summary)") {
        Then("is not a business rejection: a value-free WARN and integrity_defect{operation} +1, the TX rolls back (no business change, no evidence row)") {
            val id = created()
            val pid = proposalOf(changes.propose(propose(id, 0, "새 법인 상호", corp)))
            // 저장 결함 재현(root): 제안 요약에 실제로 바뀌지 않는 항목을 덧붙인다(runtime 계정에는 UPDATE 권한이 없다).
            com.paycore.master.MasterDbAccounts.root(container).use {
                it.prepareStatement(
                    "UPDATE paycore_master.business_change_proposal SET changes = JSON_ARRAY_APPEND(changes, '$', JSON_OBJECT('field', 'registration.phoneNumber', 'before', 'a', 'after', 'b')) " +
                        "WHERE proposal_id = ?",
                ).use { ps -> ps.setString(1, pid); ps.executeUpdate() } shouldBe 1
            }
            fun defects() = registry.find("paycore.master.admin.integrity_defect").tags("operation", "ApplyBusinessCriticalChange").counter()?.count() ?: 0.0
            val before = defects()
            val command = apply(id, 0, pid, digestOf(pid))
            val logs = CapturedLogs()
            logs.use { shouldThrow<IllegalStateException> { changes.applyWithEvidence(command) } }
            defects() shouldBe before + 1.0
            val warn = logs.lines().single { it.contains("integrity defect") }
            listOf("새 법인 상호", pid, id).filter { warn.contains(it) } shouldBe emptyList()
            listOf(version(id), displayName(id), count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId)) shouldBe
                listOf(0L, "법인 상호", 0L)
        }
    }

    Given("§3.3 1-person path switch (§7.1 TC-13 and completeRegistration variants)") {
        Then("REGISTERED name change with a reason → stored BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED; DRAFT with contract history: update and completeRegistration → the same, stays DRAFT") {
            val id = created()
            rejected(businesses.update(UpdateBusinessCommand(request(), BusinessId(id), 0, BusinessChangeReason("상호 변경"), BusinessType.CORPORATION, DisplayName("새 상호"), corp))) shouldBe
                "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"
            val draft = created(BusinessRegistrationStatus.DRAFT)
            OnboardingFixture.contracted(onboarding, BusinessId(draft))
            rejected(businesses.update(UpdateBusinessCommand(request(), BusinessId(draft), 0, BusinessChangeReason("변경"), BusinessType.CORPORATION, DisplayName("새 상호"), corp))) shouldBe
                "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"
            rejected(
                businesses.completeRegistration(
                    CompleteBusinessRegistrationCommand(request(), BusinessId(draft), 0, BusinessChangeReason("변경"), BusinessType.CORPORATION, DisplayName("새 상호"), corp),
                ),
            ) shouldBe "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"
            text("SELECT registration_status FROM business WHERE business_id = ?", draft) shouldBe "DRAFT"
            listOf(version(id), version(draft)) shouldBe listOf(0L, 0L)
        }

        Then("BR-BIZ-391 a DRAFT registration edit with an optional evidenceRef stores the original only in business_change_evidence; history says evidence=PRESENT / ABSENT; evidence is part of the request fingerprint") {
            val id = created(BusinessRegistrationStatus.DRAFT)
            val k = key()
            fun command(evidence: String?, key: com.paycore.master.merchant.application.provided.AdminIdempotencyKey = k) = UpdateBusinessCommand(
                request(key), BusinessId(id), 0, BusinessChangeReason("등록 정보 수정"), BusinessType.CORPORATION, DisplayName("고친 상호"), corp, evidence?.let(::EvidenceRef),
            )
            succeeded(businesses.update(command("REG-DOC/77#p2")))
            val historyId = count("SELECT MAX(id) FROM master_change_history WHERE aggregate_id = ?", id)
            val (_, detail) = lastHistory(id)
            detail["evidence"] shouldBe "PRESENT"
            text("SELECT CAST(detail AS CHAR) FROM master_change_history WHERE id = ?", historyId)!! shouldNotContain "REG-DOC/77"
            listOf(
                text("SELECT evidence_ref FROM business_change_evidence WHERE change_history_id = ?", historyId),
                text("SELECT business_id FROM business_change_evidence WHERE change_history_id = ?", historyId),
                text("SELECT recorded_by FROM business_change_evidence WHERE change_history_id = ?", historyId),
            ) shouldBe listOf("REG-DOC/77#p2", id, OnboardingFixture.operator.value)
            businesses.update(command("OTHER-DOC/1")) shouldBe AdminCommandResult.IdempotencyKeyReused
            businesses.update(command("REG-DOC/77#p2")) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
            succeeded(businesses.update(UpdateBusinessCommand(request(), BusinessId(id), 1, null, BusinessType.CORPORATION, DisplayName("고친 상호"), phone(corp))))
            lastHistory(id).second["evidence"] shouldBe "ABSENT"
            count("SELECT COUNT(*) FROM business_change_evidence WHERE business_id = ?", id) shouldBe 1
            // 조회: 사업자 범위 + 저장 행위자.
            query.evidence(BusinessId(id), historyId)!!.let { listOf(it.evidenceRef, it.recordedBy, it.historyId) } shouldBe listOf("REG-DOC/77#p2", OnboardingFixture.operator.value, historyId)
            query.evidence(BusinessId(created()), historyId) shouldBe null
        }

        Then("rollback: an Outbox failure on a 1-person update with evidence leaves no evidence row") {
            val id = created(BusinessRegistrationStatus.DRAFT)
            faults.after(MasterFaultPoint.OUTBOX_APPEND) { throw IllegalStateException("injected outbox failure") }
            shouldThrow<IllegalStateException> {
                businesses.update(UpdateBusinessCommand(request(), BusinessId(id), 0, BusinessChangeReason("수정"), BusinessType.CORPORATION, DisplayName("고친 상호"), corp, EvidenceRef("X-1")))
            }
            faults.reset()
            listOf(version(id), count("SELECT COUNT(*) FROM business_change_evidence WHERE business_id = ?", id)) shouldBe listOf(0L, 0L)
        }
    }

    Given("§3.1 V27 runtime privileges and §5.1 queries") {
        Then("runtime account cannot UPDATE or DELETE proposals or evidence (1142)") {
            val id = created()
            val pid = proposalOf(changes.propose(propose(id, 0, "새 법인 상호", corp)))
            runtime(container).use { c ->
                listOf(
                    c.errorCodeOfUpdate("UPDATE business_change_proposal SET reason = 'x' WHERE proposal_id = ?", pid),
                    c.errorCodeOfUpdate("DELETE FROM business_change_proposal WHERE proposal_id = ?", pid),
                    c.errorCodeOfUpdate("UPDATE business_change_evidence SET evidence_ref = 'x' WHERE business_id = ?", id),
                    c.errorCodeOfUpdate("DELETE FROM business_change_evidence WHERE business_id = ?", id),
                )
            } shouldBe listOf(1142, 1142, 1142, 1142)
        }

        Then("approval-state = version·lifecycle·status only; proposal view is masked and scoped to its business; original carries the raw content") {
            val id = created()
            val pid = proposalOf(changes.propose(propose(id, 0, "새 법인 상호", BusinessFixtures.corporationProfile().copy(representatives = listOf(BusinessFixtures.joint("캔어리대표"), BusinessFixtures.joint("박공동"))))))
            query.approvalState(BusinessId(id))!!.let { listOf(it.version, it.lifecycle, it.registrationStatus) } shouldBe listOf(0L, Lifecycle.ACTIVE, BusinessRegistrationStatus.REGISTERED)
            query.approvalState(BusinessId(BusinessFixtures.individualRegistered().business.id.value)) shouldBe null
            val view = query.proposal(BusinessId(id), BusinessChangeProposalId(pid))!!
            listOf(view.baseVersion, view.displayName, view.changedFields, view.importantFields, view.representativeChanged, view.subjectCorrection, view.requestedBy, view.contentDigest) shouldBe
                listOf(0L, "새 법인 상호", listOf("displayName", "representatives"), listOf("displayName", "representatives"), true, false, REQUESTER.value, digestOf(pid))
            view.changes.toString() shouldNotContain "캔어리대표"
            query.proposal(BusinessId(created()), BusinessChangeProposalId(pid)) shouldBe null
            val original = query.original(BusinessId(id), BusinessChangeProposalId(pid))!!
            original.content["displayName"] shouldBe "새 법인 상호"
            (original.content["representatives"] as List<*>).map { (it as Map<*, *>)["name"] } shouldBe listOf("캔어리대표", "박공동")
            query.original(BusinessId(created()), BusinessChangeProposalId(pid)) shouldBe null
        }
    }
}) {
    private companion object {
        /** 시험 쪽 정규 내용(Domain [com.paycore.master.merchant.domain.BusinessChangeProposal.contentJson]과 같은 모양 — 독립 작성). */
        fun contentJson(content: BusinessContent): String {
            val p = content.profile
            val r = p.registration
            return CanonicalJson.write(
                mapOf(
                    "businessType" to content.businessType?.name,
                    "displayName" to content.displayName.value,
                    "registration" to mapOf(
                        "corporateRegistrationNumber" to r.corporateRegistrationNumber?.value, "openingDate" to r.openingDate?.toString(), "countryCode" to r.countryCode,
                        "englishName" to r.englishName?.value, "phoneNumber" to r.phoneNumber?.value, "email" to r.email?.value,
                    ),
                    "representatives" to p.representatives.map { mapOf("name" to it.name.value, "type" to it.type.name) },
                    "categories" to p.categories.map { mapOf("type" to it.type.name, "name" to it.name.value) },
                    "addresses" to p.addresses.map { mapOf("type" to it.type.name, "postalCode" to it.postalCode.value, "baseAddress" to it.baseAddress.value, "detailAddress" to it.detailAddress?.value) },
                    "contacts" to p.contacts.map { mapOf("name" to it.name.value, "email" to it.email.value, "phoneNumber" to it.phoneNumber.value, "primary" to it.primary) },
                ),
            )
        }
    }
}
