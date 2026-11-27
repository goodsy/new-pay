package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.errorCodeOf
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.BusinessContractCommandUseCase
import com.paycore.master.merchant.application.provided.BusinessContractEventCommand
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MERCHANT-FLOW-001 FLOW-3(§4B.8 — Use Case, 실제 MySQL 8.4·runtime 계정): 새 가맹점 등록 관문 + 등록 근거 기록(BR-BIZ-319·320·327·337·340·342).
 * - 유효 사업자 계약 없음·효력 전·정지·해지 → 저장된 `BUSINESS_CONTRACT_NOT_EFFECTIVE`(가맹점·근거 행 0, 같은 키 재생). V21 이관 계약 사업자는 등록된다(BR-BIZ-334).
 * - 통과하면 같은 TX에 `merchant_registration_basis` 1행(가맹점·사업자·계약 순번·적용 중 버전·기록 시각 = lockedNow·행위자). 기록 시점 사실 — 뒤의 해지·새 버전에 바뀌지 않는다,
 *   runtime UPDATE·DELETE 1142. `MerchantRegistered` 이력·Outbox payload 키는 그대로(PAYMENT golden 불변).
 * - BR-BIZ-340: 정지·해지·만료된 사업자 계약의 기존 가맹점 후속 명령(Site 등록·가맹점 계약 등록·Revision 승인·서비스 계약·첫 MID 발급·결제수단 개통 등록)은 그대로 성공.
 * - BR-BIZ-319: 변경 접수 진행·반려는 등록에 영향 없음(유효 계약이 판정).
 * - Transaction Safety(리뷰 MAJOR-1): 근거 INSERT 직후 실패 주입 → 등록 전체 rollback·성공 결과 없음·같은 키 재전송 성공. 대조군: 근거 FK 1452.
 * - Observability(리뷰 MAJOR-2): 저장 행 불변식 위반 load → 값 없는 ERROR 경보(`alert=BUSINESS_CONTRACT_LOAD_INVARIANT`, 사업자 ID·aggregate=contract) + 결함.
 * - Concurrency(§4B.9, 실제 두 연결 경합 강제): 해지(먼저 잠금) vs 등록 → 등록 거절, 등록(먼저 잠금) vs 해지 → 둘 다 성공(등록된 가맹점은 해지 영향 없음).
 */
@MasterDbTest
class MerchantRegistrationBasisSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val businesses: BusinessProfileCommandUseCase,
    private val intakes: ContractIntakeCommandUseCase,
    private val contracts: BusinessContractCommandUseCase,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val container: MySQLContainer,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
    }

    val now: Instant = Instant.parse("2026-11-26T01:00:00.000Z")
    val today: LocalDate = LocalDate.parse("2026-11-26")
    val reason = BusinessChangeReason("계약 종료")

    fun <T> runtime(block: (Connection) -> T): T = MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)
    fun rows(sql: String, vararg args: Any?): List<String> = runtime { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun business(): BusinessId = BusinessCommandFixture.withoutMerchants(businesses).businessId
    fun register(id: BusinessId, key: com.paycore.master.merchant.application.provided.AdminRequest = request(), code: com.paycore.master.merchant.domain.MerchantCode = MerchantCodeFixtures.unique()) =
        onboarding.registerMerchant(RegisterMerchantCommand(key, id, DisplayName("관문 가맹점"), code))

    fun contracted(id: BusinessId, start: LocalDate = today, seq: Int = 1, end: LocalDate? = null) {
        val items = ContractIntakeItems(today, ContractIntakeChannel.PHONE, ActorRef("admin:adm_01JCFL0000000000000000OP01"), setOf(DesiredService.CARD_AUTH))
        intakes.open(OpenContractIntakeCommand(request(), id, ContractIntakeKind.NEW, items, null)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        intakes.move(MoveContractIntakeCommand(request(), id, seq, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, null)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
        intakes.complete(
            CompleteContractIntakeCommand(request(), id, seq, 1, ContractDocumentRef("DOC-1"), BusinessContractTerms(start, end, setOf(ServiceKind.CARD_PG_PAYMENT)), null),
        ).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
    }

    fun basis(merchant: String) = rows(
        "SELECT CONCAT_WS('|', merchant_id, business_id, contract_seq, version_no, DATE_FORMAT(recorded_at, '%Y-%m-%dT%H:%i:%s.%fZ'), recorded_by) " +
            "FROM merchant_registration_basis WHERE merchant_id = ?",
        merchant,
    )

    /** BR-BIZ-340: 기존 가맹점 후속 명령(Site·가맹점 계약·Revision 승인·서비스 계약·첫 MID·결제수단 개통 등록) — 모두 성공해야 한다. */
    fun followUps(id: BusinessId, merchant: MerchantId, at: Instant) {
        val site = com.paycore.master.merchant.domain.MerchantSiteId(
            OnboardingFixture.succeeded(onboarding.registerMerchantSite(RegisterMerchantSiteCommand(request(), merchant, ChannelType.WEB, OnboardingFixture.host()))),
        )
        val contract = com.paycore.master.merchant.domain.ContractId(
            OnboardingFixture.succeeded(
                onboarding.registerContract(
                    com.paycore.master.merchant.application.provided.RegisterContractCommand(
                        request(), id, com.paycore.master.merchant.application.provided.ScopeInput(merchant), com.paycore.master.merchant.domain.EffectivePeriod(at),
                    ),
                ),
            ),
        )
        OnboardingFixture.succeeded(
            onboarding.approveContractRevision(com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), contract, 1)),
        )
        val agreement = com.paycore.master.merchant.domain.ContractServiceAgreementId(
            OnboardingFixture.succeeded(
                onboarding.addServiceAgreement(com.paycore.master.merchant.application.provided.AddServiceAgreementCommand(request(), contract, ServiceKind.CARD_PG_PAYMENT)),
            ),
        )
        val mid = com.paycore.master.merchant.domain.MerchantAccountId(
            OnboardingFixture.succeeded(
                onboarding.issueFirstDefaultMerchantAccount(
                    com.paycore.master.merchant.application.provided.IssueFirstDefaultMerchantAccountCommand(OnboardingFixture.seedRequest(), merchant, agreement, contract, 1, site),
                ),
            ),
        )
        OnboardingFixture.succeeded(
            onboarding.registerPaymentMethodActivation(
                com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand(request(), mid, com.paycore.master.merchant.domain.PaymentMethod.CARD),
            ),
        )
    }

    Given("FLOW-3 gate on the registration command") {
        Then("no / not-yet-effective / suspended / terminated contract → stored BUSINESS_CONTRACT_NOT_EFFECTIVE (no merchant, no basis row; the same key replays)") {
            clock.set(now)
            val none = business()
            val key = request()
            val code = MerchantCodeFixtures.unique()
            register(none, key, code) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE, null, false)
            register(none, key, code) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE, null, true)
            val notYet = business().also { contracted(it, start = today.plusDays(3)) }
            val suspended = business().also { contracted(it) }.also { contracts.suspend(BusinessContractEventCommand(request(), it, 1, 0, reason, null)) }
            val terminated = business().also { contracted(it) }.also { contracts.terminate(BusinessContractEventCommand(request(), it, 1, 0, reason, null)) }
            listOf(notYet, suspended, terminated).forEach { register(it).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE }
            listOf(none, notYet, suspended, terminated).forEach { rows("SELECT COUNT(*) FROM merchant WHERE business_id = ?", it.value) shouldBe listOf("0") }
        }

        Then("BR-BIZ-337: success writes one immutable basis row (contract #1 / applying version / lockedNow / actor); termination and a new version do not change it; UPDATE / DELETE 1142") {
            clock.set(now)
            val id = business().also { contracted(it) }
            val merchant = OnboardingFixture.succeeded(register(id))
            val recorded = "$merchant|${id.value}|1|1|2026-11-26T01:00:00.000000Z|${OnboardingFixture.operator.value}"
            basis(merchant) shouldBe listOf(recorded)
            // 새 버전(변경 접수 → 계약 완료, 내일 시작) 뒤에도 근거 버전 번호 그대로.
            val items = ContractIntakeItems(today, ContractIntakeChannel.PHONE, ActorRef("admin:adm_01JCFL0000000000000000OP01"), emptySet())
            intakes.open(OpenContractIntakeCommand(request(), id, ContractIntakeKind.CHANGE, items, null)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            intakes.move(MoveContractIntakeCommand(request(), id, 2, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, null)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            intakes.complete(
                CompleteContractIntakeCommand(request(), id, 2, 1, ContractDocumentRef("DOC-2"), BusinessContractTerms(today.plusDays(1), null, setOf(ServiceKind.CARD_PG_PAYMENT)), null),
            ).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            contracts.terminate(BusinessContractEventCommand(request(), id, 1, 0, reason, null)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            basis(merchant) shouldBe listOf(recorded)
            runtime { c ->
                listOf(
                    c.errorCodeOf("UPDATE merchant_registration_basis SET version_no = 2 WHERE merchant_id = '$merchant'"),
                    c.errorCodeOf("DELETE FROM merchant_registration_basis WHERE merchant_id = '$merchant'"),
                )
            } shouldBe listOf(1142, 1142)
            // BR-BIZ-340: 해지된 사업자 계약의 기존 가맹점 후속 명령은 사업자 계약을 보지 않는다.
            onboarding.registerMerchantSite(RegisterMerchantSiteCommand(request(), MerchantId(merchant), ChannelType.WEB, OnboardingFixture.host()))
                .shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            // MerchantRegistered 이력·Outbox payload 키는 그대로(PAYMENT golden 불변 — 근거는 별도 표가 정본).
            rows(
                "SELECT CONCAT(JSON_LENGTH(payload), '|', JSON_CONTAINS_PATH(payload, 'all', '$.merchantId', '$.businessId', '$.displayName', '$.lifecycle', " +
                    "'$.entitlementRevision', '$.merchantCode', '$.occurredAt')) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'MerchantRegistered'",
                merchant,
            ) shouldBe listOf("7|1")
        }

        Then("BR-BIZ-340: under a suspended or terminated business contract an existing merchant's follow-up commands (Site, merchant contract, revision approval, CSA, first MID, activation) succeed as before") {
            clock.set(now)
            listOf<(BusinessId) -> AdminCommandResult>(
                { contracts.suspend(BusinessContractEventCommand(request(), it, 1, 0, reason, null)) },
                { contracts.terminate(BusinessContractEventCommand(request(), it, 1, 0, reason, null)) },
            ).forEach { stop ->
                val id = business().also { contracted(it) }
                val merchant = MerchantId(OnboardingFixture.succeeded(register(id)))
                stop(id).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
                followUps(id, merchant, now)
            }
        }

        Then("BR-BIZ-340 (review m-5): after the business contract expires (clock past the end date) a new merchant is refused but the existing merchant's follow-up commands succeed") {
            clock.set(now)
            val id = business().also { contracted(it, end = today) } // 종료일 = 오늘(KST) — 종료일 다음 날 00:00 KST부터 만료
            val merchant = MerchantId(OnboardingFixture.succeeded(register(id)))
            val expired = Instant.parse("2026-11-26T15:00:00.000Z") // 2026-11-27 00:00 KST
            clock.set(expired)
            register(id).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE
            followUps(id, merchant, expired)
        }

        Then("BR-BIZ-319 (review m-5): a CHANGE intake in progress or rejected does not affect registration — the effective contract decides") {
            clock.set(now)
            val id = business().also { contracted(it) }
            val items = ContractIntakeItems(today, ContractIntakeChannel.PHONE, ActorRef("admin:adm_01JCFL0000000000000000OP01"), emptySet())
            intakes.open(OpenContractIntakeCommand(request(), id, ContractIntakeKind.CHANGE, items, null)).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            register(id).shouldBeInstanceOf<AdminCommandResult.Succeeded>() // 변경 접수 상담 중
            intakes.move(MoveContractIntakeCommand(request(), id, 2, 0, ContractIntakeStatus.REJECTED, BusinessChangeReason("변경 불가"))).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            register(id).shouldBeInstanceOf<AdminCommandResult.Succeeded>() // 변경 접수 계약 불가(반려)
            rows("SELECT COUNT(*) FROM merchant_registration_basis WHERE business_id = ? AND contract_seq = 1 AND version_no = 1", id.value) shouldBe listOf("2")
        }

        Then("MAJOR-1 Transaction Safety: a failure right after the basis INSERT (flushed, before commit) rolls back merchant, code assignment, basis, history and outbox; no stored success; the same key then succeeds") {
            clock.set(now)
            val id = business().also { contracted(it) }
            val key = request()
            val code = MerchantCodeFixtures.unique()
            faults.after(MasterFaultPoint.REGISTRATION_BASIS_INSERT) { throw IllegalStateException("injected basis failure") }
            io.kotest.assertions.throwables.shouldThrowAny { register(id, key, code) }
            faults.reset()
            rows("SELECT COUNT(*) FROM merchant WHERE business_id = ?", id.value) shouldBe listOf("0")
            rows("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_code = ?", code.value) shouldBe listOf("0")
            rows("SELECT COUNT(*) FROM merchant_registration_basis WHERE business_id = ?", id.value) shouldBe listOf("0")
            rows("SELECT COUNT(*) FROM master_change_history WHERE change_type = 'MerchantRegistered' AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.businessId')) = ?", id.value) shouldBe listOf("0")
            rows("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'MerchantRegistered' AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.businessId')) = ?", id.value) shouldBe listOf("0")
            rows("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'SUCCESS'", key.idempotencyKey.value) shouldBe listOf("0")
            val retried = register(id, key, code).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            retried.replayed shouldBe false
            basis(retried.reference).size shouldBe 1
            rows("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'MerchantRegistered' AND aggregate_id = ?", retried.reference) shouldBe listOf("1")
        }

        Then("MAJOR-1 control: the basis FKs really refuse a row without its merchant or contract version (1452, runtime account)") {
            clock.set(now)
            val id = business().also { contracted(it) }
            val merchant = OnboardingFixture.succeeded(register(id))
            MasterDbAccounts.root(container).use { c ->
                c.prepareStatement("DELETE FROM merchant_registration_basis WHERE merchant_id = ?").use { it.setString(1, merchant); it.executeUpdate() } shouldBe 1
            }
            runtime { c ->
                listOf(
                    c.errorCodeOf(
                        "INSERT INTO merchant_registration_basis (merchant_id, business_id, contract_seq, version_no, recorded_at, recorded_by) " +
                            "VALUES ('mer_01JCFL00000000000000NOPE01', '${id.value}', 1, 1, '2026-11-26 01:00:00.000', 'admin:x')",
                    ),
                    c.errorCodeOf(
                        "INSERT INTO merchant_registration_basis (merchant_id, business_id, contract_seq, version_no, recorded_at, recorded_by) " +
                            "VALUES ('$merchant', '${id.value}', 1, 9, '2026-11-26 01:00:00.000', 'admin:x')",
                    ),
                )
            } shouldBe listOf(1452, 1452)
        }

        Then("MAJOR-2 Observability: stored contract rows breaking a domain invariant fail the registration (no stored rejection, no merchant) with a value-free ERROR alert") {
            clock.set(now)
            val id = business().also { contracted(it) }
            // 결함 데이터: 정지 없이 정지 해제 사건만(FK·CHECK로 못 막는 행 사이 불변식 — Domain 복원이 거부).
            runtime { c ->
                c.prepareStatement(
                    "INSERT INTO business_contract_event (business_id, contract_seq, event_seq, kind, reason, evidence_ref, actor_ref, occurred_at) " +
                        "VALUES (?, 1, 1, 'RESUME', 'CANARY-RESUME-REASON', NULL, 'admin:x', '2026-11-25 00:00:00.000')",
                ).use { it.setString(1, id.value); it.executeUpdate() }
            }
            val logger = org.slf4j.LoggerFactory.getLogger(MerchantOnboardingService::class.java) as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            val key = request()
            try {
                io.kotest.assertions.throwables.shouldThrowAny { register(id, key) }
            } finally {
                logger.detachAppender(appender)
            }
            val alerts = appender.list.filter { it.level == ch.qos.logback.classic.Level.ERROR && it.formattedMessage.contains("alert=BUSINESS_CONTRACT_LOAD_INVARIANT") }
            alerts.map { it.formattedMessage.substringAfter("alert=") } shouldBe listOf("BUSINESS_CONTRACT_LOAD_INVARIANT businessId=${id.value} aggregate=contract")
            alerts.single().formattedMessage.contains("CANARY-RESUME-REASON") shouldBe false
            rows("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind IN ('SUCCESS', 'REJECTED')", key.idempotencyKey.value) shouldBe listOf("0")
            rows("SELECT COUNT(*) FROM merchant WHERE business_id = ?", id.value) shouldBe listOf("0")
        }

        Then("BR-BIZ-334: a business with a V21 migrated contract registers merchants (basis = contract #1, version 1)") {
            clock.set(now)
            val id = business()
            runtime { c ->
                val at = "2026-11-21 03:00:00.000"
                listOf(
                    "INSERT INTO contract_intake (business_id, intake_seq, status, kind, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, " +
                        "created_at, created_by, updated_at, updated_by, version) VALUES (?, 1, 'COMPLETED', 'NEW', 1, NULL, NULL, NULL, 0, 0, '$at', 'system:migration', '$at', 'system:migration', 0)",
                    "INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by) VALUES (?, 1, 1, 1, '$at', 'system:migration')",
                    "INSERT INTO business_contract_version (business_id, contract_seq, version_no, origin_intake_seq, migrated, starts_at, start_date, end_date, document_ref, reason, " +
                        "created_at, created_by) VALUES (?, 1, 1, 1, 1, '$at', NULL, NULL, NULL, NULL, '$at', 'system:migration')",
                    "INSERT INTO business_contract_version_service (business_id, contract_seq, version_no, service_kind) VALUES (?, 1, 1, 'CARD_PG_PAYMENT')",
                ).forEach { sql -> c.prepareStatement(sql).use { it.setString(1, id.value); it.executeUpdate() } }
            }
            val merchant = OnboardingFixture.succeeded(register(id))
            basis(merchant).single().split('|').drop(2).take(2) shouldBe listOf("1", "1")
        }
    }

    Given("§4B.9 terminate vs register on real MySQL (two connections, contention forced)") {
        fun race(first: () -> AdminCommandResult, second: () -> AdminCommandResult): List<AdminCommandResult> {
            val held = AtomicBoolean(false)
            val holding = CountDownLatch(1)
            faults.after(MasterFaultPoint.CHANGE_LOG_APPEND) {
                if (held.compareAndSet(false, true)) {
                    holding.countDown()
                    val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                    while (true) {
                        val waiting = MasterDbAccounts.root(container).use { c ->
                            c.createStatement().use { st ->
                                st.executeQuery(
                                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l " +
                                        "ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA = 'paycore_master' AND l.OBJECT_NAME = 'business'",
                                ).use { rs -> rs.next(); rs.getInt(1) }
                            }
                        }
                        if (waiting > 0) break
                        check(System.nanoTime() < deadline) { "the second command never waited for the business row lock" }
                        Thread.onSpinWait()
                    }
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            val a = pool.submit<AdminCommandResult> { first() }
            check(holding.await(30, TimeUnit.SECONDS)) { "the first command never reached the history write" }
            val b = pool.submit<AdminCommandResult> { second() }
            val out = listOf(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS))
            pool.shutdown()
            faults.reset()
            return out
        }

        Then("termination committed first → the registration is BUSINESS_CONTRACT_NOT_EFFECTIVE") {
            clock.set(now)
            val id = business().also { contracted(it) }
            val (a, b) = race({ contracts.terminate(BusinessContractEventCommand(request(), id, 1, 0, reason, null)) }, { register(id) })
            a.shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            b.shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE
            rows("SELECT COUNT(*) FROM merchant WHERE business_id = ?", id.value) shouldBe listOf("0")
        }

        Then("registration committed first → both succeed; the merchant and its basis stay (BR-BIZ-321)") {
            clock.set(now)
            val id = business().also { contracted(it) }
            val (a, b) = race({ register(id) }, { contracts.terminate(BusinessContractEventCommand(request(), id, 1, 0, reason, null)) })
            val merchant = a.shouldBeInstanceOf<AdminCommandResult.Succeeded>().reference
            b.shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            basis(merchant).size shouldBe 1
        }
    }
})
