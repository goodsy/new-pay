package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.ApprovedCommandFixture.contractApproval
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.OnboardingFixture.host
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.OnboardingFixture.t0
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.MerchantOpsQuery
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer

/**
 * SITE-CONTRACT-CODE-001 SCODE-1-2·1-3·1-12·1-13·1-14(실제 MySQL 8.4 — runtime 계정 앱, 시험 조회는 runtime·root 연결).
 * - 1-2 발급: 첫 Site `-01`, 둘째 `-02`, 종료 Site가 있어도 다음 번호 증가(재사용 없음), Site·계약 순번 독립, 응답 `code` = 저장값, 9999 다음은 저장된 거절
 *   (`beginWrites` 전 — Site·계약·코드 행·revision 변화 없음, 같은 키 재생 동일). 이력 detail·Outbox payload에 `siteCode`·`contractCode`(C-12: Aggregate 필드 아님).
 * - 1-3 재생: 같은 키 재전송 → 같은 reference·code, 코드 행 1개. 같은 키·다른 내용 → 기존 409 그대로.
 * - 1-12 비노출: 권리 Snapshot payload에 코드 문자열 0건(Snapshot 불변 — Q-SC-7).
 * - 1-13 rollback: 코드 행 INSERT 직후 장애 주입 → Site·계약·이력·Outbox·admin_request 모두 없음, 같은 키 재시도 성공.
 * - 1-14 상세 누락 표시(C-15): 코드 행을 시험 관리자 연결로 지운 Site·계약 → 상세는 성공, 해당 코드 `null`, WARN 1줄(ID 원문 없음), 첫 MID 발급·계약 승인·Revision 등록은 정상.
 */
@MasterDbTest
class SiteContractCodeIssuanceSpec(
    private val service: MerchantOnboardingUseCase,
    private val query: MerchantOpsQuery,
    private val container: MySQLContainer,
    private val faults: MasterFaultInjector,
) : BehaviorSpec({

    beforeSpec { faults.reset() }
    afterAny { faults.reset() }

    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
    fun count(sql: String, vararg args: Any?): Long = text(sql, *args)!!.toLong()
    fun root(sql: String, vararg args: Any?): Int = MasterDbAccounts.root(container).use { it.update(sql, *args) }

    fun business(): BusinessId = BusinessId(succeeded(OnboardingFixture.registeredBusiness(service, OnboardingFixture.registrationNumber(), "Code Business")))
    fun merchant(business: BusinessId): MerchantId =
        MerchantId(succeeded(service.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName("Code Merchant"), MerchantCodeFixtures.unique()))))
    fun merchantCode(m: MerchantId): String = text("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? ORDER BY assignment_seq DESC LIMIT 1", m.value)!!
    fun siteCommand(m: MerchantId, key: com.paycore.master.merchant.application.provided.AdminIdempotencyKey = OnboardingFixture.key(), channel: String = host()) =
        RegisterMerchantSiteCommand(request(key), m, ChannelType.WEB, channel)
    fun contractCommand(b: BusinessId, m: MerchantId, key: com.paycore.master.merchant.application.provided.AdminIdempotencyKey = OnboardingFixture.key()) =
        RegisterContractCommand(request(key), b, ScopeInput(m), EffectivePeriod(t0))
    fun ok(r: AdminCommandResult) = r.shouldBeInstanceOf<AdminCommandResult.Succeeded>()
    fun storedSiteCode(siteId: String): String? = text("SELECT site_code FROM merchant_site_code WHERE merchant_site_id = ?", siteId)
    fun storedContractCode(contractId: String): String? = text("SELECT contract_code FROM contract_code WHERE contract_id = ?", contractId)

    Given("SCODE-1-2 a merchant registering Sites and contracts") {
        val b = business()
        val m = merchant(b)
        val mc = merchantCode(m)

        Then("first Site -01, second -02; a terminated Site still advances the number (no reuse); contract numbers are independent; response code = stored code") {
            val first = ok(service.registerMerchantSite(siteCommand(m)))
            first.code shouldBe "site-$mc-01"
            storedSiteCode(first.reference) shouldBe first.code
            val second = ok(service.registerMerchantSite(siteCommand(m)))
            second.code shouldBe "site-$mc-02"
            root("UPDATE merchant_site SET lifecycle = 'TERMINATED' WHERE merchant_site_id = ?", second.reference)
            val third = ok(service.registerMerchantSite(siteCommand(m)))
            third.code shouldBe "site-$mc-03"
            val contract = ok(service.registerContract(contractCommand(b, m)))
            contract.code shouldBe "ctr-$mc-01"
            storedContractCode(contract.reference) shouldBe contract.code
            ok(service.registerContract(contractCommand(b, m))).code shouldBe "ctr-$mc-02"
            count("SELECT COUNT(*) FROM merchant_site_code WHERE merchant_id = ? AND source = 'REGISTRATION' AND assigned_by = 'operator-1' AND issued_merchant_code = ?", m.value, mc) shouldBe 3
            // 이력 detail·Outbox payload에 코드(가산 필드).
            text("SELECT JSON_UNQUOTE(JSON_EXTRACT(detail, '$.siteCode')) FROM master_change_history WHERE change_type = 'MerchantSiteRegistered' AND aggregate_id = ?", first.reference) shouldBe first.code
            text("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.siteCode')) FROM outbox_event WHERE event_type = 'MerchantSiteRegistered' AND aggregate_id = ?", first.reference) shouldBe first.code
            text("SELECT JSON_UNQUOTE(JSON_EXTRACT(detail, '$.contractCode')) FROM master_change_history WHERE change_type = 'ContractRegistered' AND aggregate_id = ?", contract.reference) shouldBe contract.code
            text("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.contractCode')) FROM outbox_event WHERE event_type = 'ContractRegistered' AND aggregate_id = ?", contract.reference) shouldBe contract.code
        }

        Then("the 10000th code is a stored SITE_CODE_EXHAUSTED / CONTRACT_CODE_EXHAUSTED decided before beginWrites: no Site, contract, code row, history or revision change; the same key replays it") {
            val site = ok(service.registerMerchantSite(siteCommand(m)))
            val contract = ok(service.registerContract(contractCommand(b, m)))
            // 시험 관리자 연결: 가장 큰 순번을 9999로 옮긴다(CHECK 식을 만족하는 값 — 정상 경로로는 9999건 등록이 필요).
            root("UPDATE merchant_site_code SET code_seq = 9999, site_code = CONCAT('site-', issued_merchant_code, '-9999') WHERE merchant_site_id = ?", site.reference)
            root("UPDATE contract_code SET code_seq = 9999, contract_code = CONCAT('ctr-', issued_merchant_code, '-9999') WHERE contract_id = ?", contract.reference)
            val before = listOf(
                count("SELECT COUNT(*) FROM merchant_site WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", m.value),
                count("SELECT COUNT(*) FROM merchant_site_code WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM contract_code WHERE merchant_id = ?", m.value),
                count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM master_change_history"),
            )
            val siteKey = OnboardingFixture.key()
            val contractKey = OnboardingFixture.key()
            service.registerMerchantSite(siteCommand(m, siteKey, "exhausted-site.example.test")) shouldBe AdminCommandResult.Rejected(MasterRejection.SITE_CODE_EXHAUSTED, null, false)
            service.registerContract(contractCommand(b, m, contractKey)) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_CODE_EXHAUSTED, null, false)
            service.registerMerchantSite(siteCommand(m, siteKey, "exhausted-site.example.test")) shouldBe AdminCommandResult.Rejected(MasterRejection.SITE_CODE_EXHAUSTED, null, true)
            service.registerContract(contractCommand(b, m, contractKey)) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_CODE_EXHAUSTED, null, true)
            listOf(
                count("SELECT COUNT(*) FROM merchant_site WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", m.value),
                count("SELECT COUNT(*) FROM merchant_site_code WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM contract_code WHERE merchant_id = ?", m.value),
                count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM master_change_history"),
            ) shouldBe before
        }
    }

    Given("SCODE-1-3 replay of a registration") {
        val b = business()
        val m = merchant(b)

        Then("the same key and content replays the same reference and code with one code row; the same key with other content stays 409") {
            val key = OnboardingFixture.key()
            val channel = host()
            val first = ok(service.registerMerchantSite(siteCommand(m, key, channel)))
            val replay = ok(service.registerMerchantSite(siteCommand(m, key, channel)))
            listOf(replay.reference, replay.code, replay.replayed) shouldBe listOf(first.reference, first.code, true)
            count("SELECT COUNT(*) FROM merchant_site_code WHERE merchant_id = ?", m.value) shouldBe 1
            service.registerMerchantSite(siteCommand(m, key, host())) shouldBe AdminCommandResult.IdempotencyKeyReused

            val contractKey = OnboardingFixture.key()
            val contract = ok(service.registerContract(contractCommand(b, m, contractKey)))
            val contractReplay = ok(service.registerContract(contractCommand(b, m, contractKey)))
            listOf(contractReplay.reference, contractReplay.code, contractReplay.replayed) shouldBe listOf(contract.reference, contract.code, true)
            count("SELECT COUNT(*) FROM contract_code WHERE merchant_id = ?", m.value) shouldBe 1
            // 다른 등록(가맹점·CSA·Revision)에는 코드가 없다(Q-SC-6).
            ok(service.registerMerchant(RegisterMerchantCommand(request(), b, DisplayName("Code Merchant"), MerchantCodeFixtures.unique()))).code shouldBe null
        }
    }

    Given("SCODE-1-13 a failure right after the code row INSERT (same MASTER TX, before commit)") {
        Then("the Site / contract, its history, Outbox and admin_request are all rolled back; the same key then succeeds with -01") {
            val b = business()
            val m = merchant(b)
            val mc = merchantCode(m)
            val siteKey = OnboardingFixture.key()
            val channel = host()
            faults.after(MasterFaultPoint.SITE_CODE_INSERT) { throw IllegalStateException("injected after site code insert") }
            shouldThrow<IllegalStateException> { service.registerMerchantSite(siteCommand(m, siteKey, channel)) }
            val contractKey = OnboardingFixture.key()
            faults.after(MasterFaultPoint.CONTRACT_CODE_INSERT) { throw IllegalStateException("injected after contract code insert") }
            shouldThrow<IllegalStateException> { service.registerContract(contractCommand(b, m, contractKey)) }
            faults.reset()
            listOf(
                count("SELECT COUNT(*) FROM merchant_site WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", m.value),
                count("SELECT COUNT(*) FROM merchant_site_code WHERE merchant_id = ?", m.value), count("SELECT COUNT(*) FROM contract_code WHERE merchant_id = ?", m.value),
                count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key IN (?, ?)", siteKey.value, contractKey.value),
                count("SELECT COUNT(*) FROM master_change_history WHERE change_type IN ('MerchantSiteRegistered', 'ContractRegistered') AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.merchantId')) = ?", m.value) +
                    count("SELECT COUNT(*) FROM master_change_history WHERE change_type = 'ContractRegistered' AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.scopeMerchantId')) = ?", m.value),
                count("SELECT COUNT(*) FROM outbox_event WHERE event_type IN ('MerchantSiteRegistered', 'ContractRegistered') AND (JSON_UNQUOTE(JSON_EXTRACT(payload, '$.merchantId')) = ? OR JSON_UNQUOTE(JSON_EXTRACT(payload, '$.scopeMerchantId')) = ?)", m.value, m.value),
                count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", m.value),
            ) shouldBe listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
            ok(service.registerMerchantSite(siteCommand(m, siteKey, channel))).let { listOf(it.code, it.replayed) } shouldBe listOf("site-$mc-01", false)
            ok(service.registerContract(contractCommand(b, m, contractKey))).let { listOf(it.code, it.replayed) } shouldBe listOf("ctr-$mc-01", false)
        }
    }

    Given("SCODE-1-14 / C-15 code rows removed by a test admin connection (a defect)") {
        Then("the detail answers with null siteCode / contractCode (others present) and one WARN line per kind without IDs; first MID issuance, contract approval and revision registration still succeed") {
            val ready = readyMerchant(service, approved = true)
            val mc = merchantCode(ready.merchantId)
            root("DELETE FROM merchant_site_code WHERE merchant_site_id = ?", ready.siteId.value) shouldBe 1
            root("DELETE FROM contract_code WHERE contract_id = ?", ready.contractId.value) shouldBe 1
            val detail = CapturedLogs().use { logs ->
                val view = checkNotNull(query.detail(ready.merchantId))
                val lines = logs.lines()
                lines.filter { it.contains("site_code_missing") }.size shouldBe 1
                lines.filter { it.contains("contract_code_missing") }.size shouldBe 1
                lines.single { it.contains("site_code_missing") }.let { it.contains("count=1") shouldBe true }
                lines.filter { it.contains(ready.siteId.value) || it.contains(ready.contractId.value) || it.contains(ready.merchantId.value) }.shouldBeEmpty()
                view
            }
            detail.sites.associate { it.merchantSiteId to it.siteCode } shouldBe mapOf(ready.siteId.value to null, ready.secondSiteId.value to "site-$mc-02")
            detail.contracts.single().contractCode shouldBe null
            // 코드는 판정 재료가 아니다 — 명령은 막히지 않는다.
            ok(service.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            val other = ContractId(ok(service.registerContract(contractCommand(ready.businessId, ready.merchantId))).reference)
            root("DELETE FROM contract_code WHERE contract_id = ?", other.value) shouldBe 1
            ok(service.registerContractRevision(RegisterContractRevisionCommand(request(), other, ScopeInput(ready.merchantId), EffectivePeriod(t0))))
            ok(service.approveContractRevisionWithEvidence(contractApproval(other)))
            // 정상 상세: 코드가 있으면 그대로(다른 가맹점).
            val fresh = readyMerchant(service, approved = false)
            val freshCode = merchantCode(fresh.merchantId)
            checkNotNull(query.detail(fresh.merchantId)).let { v ->
                v.sites.map { it.siteCode }.toSet() shouldBe setOf("site-$freshCode-01", "site-$freshCode-02")
                v.contracts.single().contractCode shouldBe "ctr-$freshCode-01"
            }
        }
    }

    Given("SCODE-1-12 / Q-SC-7 the entitlement Snapshot after a first MID") {
        Then("the latest Snapshot payload carries no Site / contract code string") {
            val ready = readyMerchant(service, approved = true)
            val mc = merchantCode(ready.merchantId)
            ok(service.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            storedSiteCode(ready.siteId.value) shouldBe "site-$mc-01"
            val payload = latestSnapshot(container, ready.merchantId).toString()
            payload shouldNotContain "site-$mc"
            payload shouldNotContain "ctr-$mc"
            payload shouldNotContain "siteCode"
            payload shouldNotContain "contractCode"
        }
    }
})
