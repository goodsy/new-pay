package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.rowsOf
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer

/**
 * MASTER 관리 명령 use case를 실제 MySQL 8.4.11(runtime 계정 권한)에서 확인한다(MASTER-DOMAIN-001).
 * 확정 규칙: 최초 Default 발급의 MID·Default·PRIMARY·요청 결과·Outbox 한 TX(구조 경계 §1), 승인된 Revision만 근거(초기·INV-17, D-MD-003),
 * 등록 중복(D-MD-008), 같은 키·같은 내용 = 저장 결과 재생, 같은 키·다른 내용 = 충돌(CLAUDE.md 핵심 규칙), 업무 거절도 저장·재생.
 */
@MasterDbTest
class MerchantOnboardingServiceSpec(
    private val service: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    afterEach { clock.reset() }

    val t0 = OnboardingFixture.t0

    fun scalar(sql: String, vararg args: Any?): Any? = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
        }
    }

    Given("a Merchant prepared through the official commands (approved MERCHANT-scope contract with a card agreement)") {
        Then("the first Default issuance stores MID, Default, PRIMARY, history, outbox and advances the entitlement revision in one go") {
            val ready = readyMerchant(service)
            val before = rowsOf(container, ready.merchantId)
            val result = service.issueFirstDefaultMerchantAccount(ready.issueCommand()).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            result.replayed shouldBe false
            result.reference shouldStartWith "mid_"

            rowsOf(container, ready.merchantId) shouldBe before.copy(
                accounts = 1, defaults = 1, assignments = 1, outbox = 3, history = 3, entitlement = before.entitlement + 1,
            )
            mapOf(
                "default" to scalar("SELECT merchant_account_id FROM default_designation WHERE merchant_id = ?", ready.merchantId.value),
                "primary" to scalar(
                    "SELECT CONCAT(role, '/', status, '/', merchant_site_id, '/', IFNULL(effective_to, 'open')) FROM merchant_account_site_assignment " +
                        "WHERE merchant_account_id = ?",
                    result.reference,
                ),
                "basis" to scalar(
                    "SELECT CONCAT(issuing_contract_id, '#', issuing_revision_no, '/', issuing_agreement_id, '/', lifecycle) FROM merchant_account " +
                        "WHERE merchant_account_id = ?",
                    result.reference,
                ),
            ) shouldBe mapOf(
                "default" to result.reference,
                "primary" to "PRIMARY/ACTIVE/${ready.siteId.value}/open",
                "basis" to "${ready.contractId.value}#1/${ready.agreementId.value}/ACTIVE",
            )
        }

        Then("the same key with the same content replays the stored result without new rows; a different content is IdempotencyKeyReused") {
            val ready = readyMerchant(service)
            val command = ready.issueCommand()
            val first = succeeded(service.issueFirstDefaultMerchantAccount(command))
            val rows = rowsOf(container, ready.merchantId)

            service.issueFirstDefaultMerchantAccount(command.copy(request = command.request.copy(correlationId = "corr-retry"))) shouldBe
                AdminCommandResult.Succeeded(first, replayed = true)
            service.issueFirstDefaultMerchantAccount(command.copy(primarySiteId = ready.secondSiteId)) shouldBe
                AdminCommandResult.IdempotencyKeyReused
            rowsOf(container, ready.merchantId) shouldBe rows
        }

        Then("a second first-Default issuance with another key is rejected with the existing Default as reference, stored and replayed") {
            val ready = readyMerchant(service)
            val first = succeeded(service.issueFirstDefaultMerchantAccount(ready.issueCommand()))
            val second = ready.issueCommand(site = ready.secondSiteId)
            val rejected = AdminCommandResult.Rejected(MasterRejection.DEFAULT_ALREADY_ISSUED, first, replayed = false)
            service.issueFirstDefaultMerchantAccount(second) shouldBe rejected
            service.issueFirstDefaultMerchantAccount(second) shouldBe rejected.copy(replayed = true)
            rowsOf(container, ready.merchantId).accounts shouldBe 1
        }

        Then("registering the CARD activation records it once as UNDER_REVIEW (D-MD-017) and a duplicate is rejected") {
            val ready = readyMerchant(service)
            val mid = MerchantAccountId(succeeded(service.issueFirstDefaultMerchantAccount(ready.issueCommand())))
            val activation = succeeded(service.registerPaymentMethodActivation(RegisterPaymentMethodActivationCommand(request(), mid, PaymentMethod.CARD)))
            service.registerPaymentMethodActivation(RegisterPaymentMethodActivationCommand(request(), mid, PaymentMethod.CARD)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.ACTIVATION_DUPLICATE, activation, replayed = false)
            scalar(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.status')) FROM outbox_event WHERE aggregate_id = ?", activation,
            ) shouldBe "UNDER_REVIEW"
            scalar("SELECT status FROM payment_method_activation WHERE activation_id = ?", activation) shouldBe "UNDER_REVIEW"
        }
    }

    Given("issuance inputs that break the confirmed rules (rejections leave no MID, Default, link or event)") {
        Then("a contract whose revision is not approved is rejected (Production MID requires an approved contract, D-MD-003)") {
            val ready = readyMerchant(service, approved = false)
            service.issueFirstDefaultMerchantAccount(ready.issueCommand()) shouldBe
                AdminCommandResult.Rejected(MasterRejection.REVISION_NOT_APPROVED, null, replayed = false)
            rowsOf(container, ready.merchantId).let { listOf(it.accounts, it.defaults, it.assignments, it.outbox, it.history) } shouldBe
                listOf(0L, 0L, 0L, 0L, 0L)
        }

        Then("a primary site of another Merchant of the same Business is rejected") {
            val ready = readyMerchant(service)
            val sibling = succeeded(service.registerMerchant(RegisterMerchantCommand(request(), ready.businessId, DisplayName("Sibling"), com.paycore.master.merchant.domain.MerchantCodeFixtures.unique())))
            val siblingSite = succeeded(
                service.registerMerchantSite(
                    RegisterMerchantSiteCommand(request(), com.paycore.master.merchant.domain.MerchantId(sibling), ChannelType.WEB, OnboardingFixture.host()),
                ),
            )
            service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = com.paycore.master.merchant.domain.MerchantSiteId(siblingSite))) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SITE_OF_OTHER_MERCHANT, null, replayed = false)
        }

        Then("a site outside a SITES-scoped approved revision is rejected; a listed site is accepted") {
            val ready = readyMerchant(service, approved = false)
            val revision = succeeded(
                service.registerContractRevision(
                    RegisterContractRevisionCommand(request(), ready.contractId, ScopeInput(ready.merchantId, setOf(ready.siteId)), EffectivePeriod(OnboardingFixture.t0)),
                ),
            )
            revision shouldBe "${ready.contractId.value}#2"
            succeeded(service.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), ready.contractId, 2))) // D-MOPS-008 ②: seed 경로 행위자
            service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = ready.secondSiteId).copy(revisionNo = 2)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SITE_OUTSIDE_CONTRACT_SCOPE, null, replayed = false)
            service.issueFirstDefaultMerchantAccount(ready.issueCommand().copy(revisionNo = 2)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        }
    }

    Given("a contract whose later SITES revision supersedes the MERCHANT revision (reviewer repro, D-MD-011·012)") {
        // Rev1 MERCHANT [t0, ∞) 승인, Rev2 SITES{siteA} [t5, ∞) 승인 → t6에 적용 Revision은 Rev2(번호가 가장 큰 것).
        val t5 = t0.plusSeconds(5)
        val t6 = t0.plusSeconds(6)

        fun supersededScenario(): OnboardingFixture.ReadyMerchant {
            val ready = readyMerchant(service)
            succeeded(
                service.registerContractRevision(
                    RegisterContractRevisionCommand(request(), ready.contractId, ScopeInput(ready.merchantId, setOf(ready.siteId)), EffectivePeriod(t5)),
                ),
            )
            succeeded(service.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), ready.contractId, 2))) // D-MOPS-008 ②: seed 경로 행위자
            return ready
        }

        Then("at t6, citing the superseded Rev1 with site B is rejected (REVISION_NOT_APPLICABLE) and nothing is issued") {
            val ready = supersededScenario()
            clock.set(t6)
            service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = ready.secondSiteId, revisionNo = 1)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.REVISION_NOT_APPLICABLE, null, replayed = false)
            rowsOf(container, ready.merchantId).accounts shouldBe 0
        }

        Then("at t6, citing Rev2 with site B is outside its SITES scope; citing Rev2 with site A is issued") {
            val ready = supersededScenario()
            clock.set(t6)
            service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = ready.secondSiteId, revisionNo = 2)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SITE_OUTSIDE_CONTRACT_SCOPE, null, replayed = false)
            service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = ready.siteId, revisionNo = 2))
                .shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        }

        Then("before t5 Rev1 is still the applicable revision, so citing Rev1 is issued and citing the not yet effective Rev2 is rejected") {
            val ready = supersededScenario()
            clock.set(t0.plusSeconds(1))
            service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = ready.siteId, revisionNo = 2)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.REVISION_NOT_APPLICABLE, null, replayed = false)
            service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = ready.secondSiteId, revisionNo = 1))
                .shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        }
    }

    Given("a Merchant whose only approved revision starts in the future (pre-issuance, D-MD-015)") {
        Then("the first Default MID is issued now on that revision; nothing applies yet, so payment eligibility stays a separate decision") {
            val business = com.paycore.master.merchant.domain.BusinessId(
                succeeded(service.registerBusiness(RegisterBusinessCommand(request(), OnboardingFixture.registrationNumber(), DisplayName("Future")))),
            )
            val merchant = com.paycore.master.merchant.domain.MerchantId(
                succeeded(service.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName("Future Merchant"), com.paycore.master.merchant.domain.MerchantCodeFixtures.unique()))),
            )
            val site = com.paycore.master.merchant.domain.MerchantSiteId(
                succeeded(service.registerMerchantSite(RegisterMerchantSiteCommand(request(), merchant, ChannelType.WEB, OnboardingFixture.host()))),
            )
            val contract = com.paycore.master.merchant.domain.ContractId(
                succeeded(service.registerContract(RegisterContractCommand(request(), business, ScopeInput(merchant), EffectivePeriod(t0.plusSeconds(86_400))))),
            )
            succeeded(service.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), contract, 1))) // D-MOPS-008 ②: seed 경로 행위자
            val agreement = com.paycore.master.merchant.domain.ContractServiceAgreementId(
                succeeded(service.addServiceAgreement(AddServiceAgreementCommand(request(), contract, ServiceKind.CARD_PG_PAYMENT))),
            )
            val mid = succeeded(
                service.issueFirstDefaultMerchantAccount(
                    com.paycore.master.merchant.application.provided.IssueFirstDefaultMerchantAccountCommand(OnboardingFixture.seedRequest(), merchant, agreement, contract, 1, site), // D-MOPS-008 ②: seed 경로 행위자
                ),
            )
            rowsOf(container, merchant).let { listOf(it.accounts, it.defaults, it.assignments) } shouldBe listOf(1L, 1L, 1L)
            scalar("SELECT issuing_revision_no FROM merchant_account WHERE merchant_account_id = ?", mid).toString() shouldBe "1"
        }
    }

    Given("contract revision rules (D-MD-013, D-MD-014) and the millisecond boundary (review m-4)") {
        Then("a revision targeting another Merchant of the same Business is rejected (a new contract is required)") {
            val ready = readyMerchant(service)
            val sibling = com.paycore.master.merchant.domain.MerchantId(
                succeeded(service.registerMerchant(RegisterMerchantCommand(request(), ready.businessId, DisplayName("Sibling"), com.paycore.master.merchant.domain.MerchantCodeFixtures.unique()))),
            )
            service.registerContractRevision(
                RegisterContractRevisionCommand(request(), ready.contractId, ScopeInput(sibling), EffectivePeriod(t0)),
            ) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_MERCHANT_MISMATCH, null, replayed = false)
        }

        Then("approving a revision whose effective period has already ended is rejected (no approval fact recorded)") {
            val ready = readyMerchant(service)
            succeeded(
                service.registerContractRevision(
                    RegisterContractRevisionCommand(request(), ready.contractId, ScopeInput(ready.merchantId), EffectivePeriod(t0, t0.plusSeconds(1))),
                ),
            )
            clock.set(t0.plusSeconds(2))
            service.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), ready.contractId, 2)) shouldBe // D-MOPS-008 ②: seed 경로 행위자
                AdminCommandResult.Rejected(MasterRejection.REVISION_ENDED, null, replayed = false)
            scalar(
                "SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ? AND revision_no = 2", ready.contractId.value,
            ).toString() shouldBe "0"
        }

        Then("a period shorter than the stored millisecond precision is rejected (EFFECTIVE_PERIOD_EMPTY) instead of being rounded by MySQL") {
            val ready = readyMerchant(service)
            val key = OnboardingFixture.key()
            val tiny = EffectivePeriod(t0.plusNanos(100_000), t0.plusNanos(400_000))
            val command = RegisterContractRevisionCommand(request(key), ready.contractId, ScopeInput(ready.merchantId), tiny)
            service.registerContractRevision(command) shouldBe
                AdminCommandResult.Rejected(MasterRejection.EFFECTIVE_PERIOD_EMPTY, null, replayed = false)
            // 같은 ms 안의 다른 표기는 저장 정밀도로 같은 요청이다(fingerprint = 저장 정밀도) → 저장된 거절 재생.
            service.registerContractRevision(command.copy(effectivePeriod = EffectivePeriod(t0.plusNanos(300_000), t0.plusNanos(900_000)))) shouldBe
                AdminCommandResult.Rejected(MasterRejection.EFFECTIVE_PERIOD_EMPTY, null, replayed = true)
        }

        Then("sub-millisecond inputs are stored truncated to milliseconds") {
            val ready = readyMerchant(service)
            val reference = succeeded(
                service.registerContractRevision(
                    RegisterContractRevisionCommand(
                        request(), ready.contractId, ScopeInput(ready.merchantId), EffectivePeriod(t0.plusNanos(1_999_999), t0.plusSeconds(1).plusNanos(500_000)),
                    ),
                ),
            )
            reference shouldBe "${ready.contractId.value}#2"
            scalar(
                "SELECT CONCAT(CAST(effective_from AS CHAR), '/', CAST(effective_to AS CHAR)) FROM contract_revision WHERE contract_id = ? AND revision_no = 2",
                ready.contractId.value,
            ) shouldBe "2026-10-09 02:00:00.001/2026-10-09 02:00:01.000"
        }
    }

    Given("registration and contract commands") {
        Then("a duplicate registration number is rejected with the existing Business as reference (D-MD-008)") {
            val number = OnboardingFixture.registrationNumber()
            val first = succeeded(service.registerBusiness(RegisterBusinessCommand(request(), number, DisplayName("First"))))
            service.registerBusiness(RegisterBusinessCommand(request(), number, DisplayName("Second"))) shouldBe
                AdminCommandResult.Rejected(MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE, first, replayed = false)
        }

        Then("the registration number appears only masked in the audit history and never in the outbox payload (D-MD-008)") {
            val number = OnboardingFixture.registrationNumber()
            val business = succeeded(service.registerBusiness(RegisterBusinessCommand(request(), number, DisplayName("Masked"))))
            val history = scalar("SELECT CAST(detail AS CHAR) FROM master_change_history WHERE aggregate_id = ?", business).toString()
            val payload = scalar("SELECT CAST(payload AS CHAR) FROM outbox_event WHERE aggregate_id = ?", business).toString()
            history shouldNotContain number.value
            history.contains(number.masked()) shouldBe true
            payload shouldNotContain number.value.substring(3)
        }

        Then("an active channel identifier already used by another Merchant is rejected (D-MD-008)") {
            val ready = readyMerchant(service)
            val host = OnboardingFixture.host()
            val first = succeeded(service.registerMerchantSite(RegisterMerchantSiteCommand(request(), ready.merchantId, ChannelType.WEB, host)))
            val other = readyMerchant(service)
            service.registerMerchantSite(RegisterMerchantSiteCommand(request(), other.merchantId, ChannelType.WEB, host)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SITE_CHANNEL_DUPLICATE, first, replayed = false)
        }

        Then("a contract scoped to a Merchant of another Business is rejected; a second card agreement is rejected; approving twice is rejected") {
            val ready = readyMerchant(service)
            val stranger = readyMerchant(service)
            service.registerContract(RegisterContractCommand(request(), ready.businessId, ScopeInput(stranger.merchantId), EffectivePeriod(OnboardingFixture.t0))) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SCOPE_MERCHANT_OF_OTHER_BUSINESS, null, replayed = false)
            service.addServiceAgreement(AddServiceAgreementCommand(request(), ready.contractId, ServiceKind.CARD_PG_PAYMENT)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SERVICE_KIND_DUPLICATE, ready.agreementId.value, replayed = false)
            service.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), ready.contractId, 1)) shouldBe // D-MOPS-008 ②: seed 경로 행위자
                AdminCommandResult.Rejected(MasterRejection.REVISION_ALREADY_APPROVED, null, replayed = false)
        }

        Then("revision approval advances the scope Merchant's entitlement revision and records the approver") {
            val ready = readyMerchant(service, approved = false)
            val before = rowsOf(container, ready.merchantId).entitlement
            succeeded(service.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), ready.contractId, 1))) // D-MOPS-008 ②: seed 경로 행위자
            rowsOf(container, ready.merchantId).entitlement shouldBe before + 1
            scalar(
                "SELECT approved_by FROM contract_revision_approval WHERE contract_id = ? AND revision_no = 1", ready.contractId.value,
            ) shouldBe OnboardingFixture.seedActor.value // 기대값 변경(D-MOPS-008 ②, C-26): 근거 없는 승인은 seed 행위자만 실행할 수 있어 기록된 승인자도 seed 행위자다(이전 operator-1)
        }
    }

    Given("D-MOPS-006 ⑤ / MERCHANT-OPS-002 §3.4: a contract start before the KST date of the decision time (clock t0 = KST 2026-10-09 11:00)") {
        val kstToday = java.time.Instant.parse("2026-10-08T15:00:00Z") // KST 10-09 00:00
        fun contractRows(merchant: com.paycore.master.merchant.domain.MerchantId) = listOf(
            scalar("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", merchant.value).toString().toLong(),
            scalar(
                "SELECT COUNT(*) FROM master_change_history WHERE change_type = 'ContractRegistered' AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.scopeMerchantId')) = ?",
                merchant.value,
            ).toString().toLong(),
            scalar(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ContractRegistered' AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.scopeMerchantId')) = ?",
                merchant.value,
            ).toString().toLong(),
        )

        Then("one millisecond before today's KST midnight is a stored rejection replayed for the same key; no contract row, history or outbox; today's KST midnight is accepted (control)") {
            val ready = readyMerchant(service)
            val before = contractRows(ready.merchantId)
            val key = OnboardingFixture.key()
            val command = RegisterContractCommand(request(key), ready.businessId, ScopeInput(ready.merchantId), EffectivePeriod(kstToday.minusMillis(1)))
            service.registerContract(command) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_START_RETROACTIVE, null, replayed = false)
            service.registerContract(command) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_START_RETROACTIVE, null, replayed = true)
            contractRows(ready.merchantId) shouldBe before
            scalar("SELECT result_code FROM admin_request WHERE idempotency_key = ?", key.value) shouldBe "CONTRACT_START_RETROACTIVE"

            succeeded(service.registerContract(RegisterContractCommand(request(), ready.businessId, ScopeInput(ready.merchantId), EffectivePeriod(kstToday)))) shouldStartWith "ctr_"
            contractRows(ready.merchantId) shouldBe before.map { it + 1 }
        }

        // 이 시험은 판정이 서버 시계 값(KST 날짜)을 쓰는지만 확인한다 — 잠금 뒤에 시계를 읽는 순서(L-8)는 여기서 관찰하지 않는다.
        Then("the decision uses the server clock value: the same command is accepted on KST 10-08 and rejected once the clock is past KST midnight (L-8 clock value only)") {
            val ready = readyMerchant(service)
            val start = EffectivePeriod(java.time.Instant.parse("2026-10-07T15:00:00Z")) // KST 10-08 00:00
            clock.set(java.time.Instant.parse("2026-10-08T14:59:59.999Z")) // KST 10-08 23:59:59.999
            succeeded(service.registerContract(RegisterContractCommand(request(), ready.businessId, ScopeInput(ready.merchantId), start))) shouldStartWith "ctr_"
            clock.set(kstToday)
            service.registerContract(RegisterContractCommand(request(), ready.businessId, ScopeInput(ready.merchantId), start)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.CONTRACT_START_RETROACTIVE, null, replayed = false)
        }

        Then("D-MOPS-007 ② (Q-MOPS-18 answered — expectation reversed from the B1 n-1 regression): a revision starting before today's KST date is a stored rejection replayed for the same key; revision count, history and outbox unchanged") {
            clock.set(t0)
            val ready = readyMerchant(service)
            fun revisionRows() = listOf(
                scalar("SELECT COUNT(*) FROM contract_revision WHERE contract_id = ?", ready.contractId.value).toString().toLong(),
                scalar(
                    "SELECT COUNT(*) FROM master_change_history WHERE change_type = 'ContractRevisionRegistered' AND aggregate_id = ?", ready.contractId.value,
                ).toString().toLong(),
                scalar(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'ContractRevisionRegistered' AND aggregate_id = ?", ready.contractId.value,
                ).toString().toLong(),
            )
            val before = revisionRows()
            val key = OnboardingFixture.key()
            val past = EffectivePeriod(t0.minus(java.time.Duration.ofDays(3)))
            val command = RegisterContractRevisionCommand(request(key), ready.contractId, ScopeInput(ready.merchantId), past)
            service.registerContractRevision(command) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_START_RETROACTIVE, null, replayed = false)
            service.registerContractRevision(command) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_START_RETROACTIVE, null, replayed = true)
            revisionRows() shouldBe before
            scalar("SELECT result_code FROM admin_request WHERE idempotency_key = ?", key.value) shouldBe "CONTRACT_START_RETROACTIVE"

            // 대조군: 오늘 KST 00:00 시작은 받는다(같은 날 앞선 시각 허용 — §3.4 경계와 같다).
            succeeded(
                service.registerContractRevision(RegisterContractRevisionCommand(request(), ready.contractId, ScopeInput(ready.merchantId), EffectivePeriod(kstToday))),
            ) shouldBe "${ready.contractId.value}#2"
            revisionRows() shouldBe before.map { it + 1 }
        }
    }
})
