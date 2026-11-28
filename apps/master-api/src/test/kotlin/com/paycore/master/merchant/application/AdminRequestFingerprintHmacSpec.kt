package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.integration.HmacRequestFingerprintDigester
import com.paycore.master.merchant.application.OnboardingFixture.key
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessFixtures.individualProfile
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.scalar
import com.paycore.master.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.mysql.MySQLContainer
import java.util.Base64

/**
 * SEC-BIZ-001 M-2(D-MOPS-010 ②, D-GOV-001 ②, MERCHANT-OPS-002 §9.29·C-46·C-47 rev): MASTER 관리 명령 요청 지문 = `h1:` + HMAC-SHA256(fingerprint 키, canonical 전체) — 실제 MySQL.
 * ① 저장 지문은 키 없이 원문 사전 대조로 맞출 수 없다(무염 m1 계산·다른 키 계산과 다르고, 원문 문자열을 담지 않는다). ② 같은 키·같은 내용 = 재생, 같은 키·다른 내용 = 409.
 * ③ HMAC 전(`m1:`) 저장 행: **Demo**(`isDemo()=true`)에서만 같은 요청의 legacy 계산으로 비교 — 같은 내용 재생·다른 내용 409, 저장 값은 바꾸지 않는다(append-only).
 * **LIVE**(`isDemo()=false`)는 호환 비교가 꺼져 같은 내용도 409(L-25 제거 조건 전까지의 임시 경로).
 */
@MasterDbTest
class AdminRequestFingerprintHmacSpec(
    private val service: BusinessProfileCommandUseCase,
    private val digester: RequestFingerprintDigester,
    private val mode: SwitchableDeploymentMode,
    private val container: MySQLContainer,
) : BehaviorSpec({

    afterEach { mode.reset() }

    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }
    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun stored(operation: AdminOperation, k: AdminIdempotencyKey) =
        text("SELECT request_fingerprint FROM admin_request WHERE operation = ? AND idempotency_key = ?", operation.code, k.value)!!

    fun create(k: AdminIdempotencyKey = key(), name: String = "지문 상호", profile: com.paycore.master.merchant.domain.BusinessProfile = individualProfile()) =
        CreateBusinessCommand(request(k), BusinessRegistrationStatus.REGISTERED, BusinessType.INDIVIDUAL, BusinessFixtures.registrationNumber(), DisplayName(name), profile)

    /** HMAC 전 코드가 저장했을 행을 만든다: 같은 키·같은 내용으로 성공시킨 뒤 지문만 그 요청의 legacy `m1:` 값으로 되돌린다(root — 시험 준비 전용). */
    fun asLegacyRow(operation: AdminOperation, k: AdminIdempotencyKey, fields: List<String?>) {
        MasterDbAccounts.root(container).use { root ->
            root.update(
                "UPDATE paycore_master.admin_request SET request_fingerprint = ? WHERE operation = ? AND idempotency_key = ?",
                AdminCommandExecutor.legacyFingerprintOf(operation, fields), operation.code, k.value,
            ) shouldBe 1
        }
    }

    Given("① the stored fingerprint of a business create carrying personal data (canary)") {
        val k = key()
        val command = create(k, profile = BusinessFixtures.canaryProfile())
        val id = succeeded(service.create(command))
        val fields = BusinessCommandFingerprintFields.of(command)
        val value = stored(AdminOperation.CREATE_BUSINESS, k)

        Then("it is h1: + 64 hex = HMAC of the whole canonical string with the configured key") {
            value shouldMatch Regex("^h1:[0-9a-f]{64}$")
            value shouldBe AdminCommandExecutor.fingerprintOf(digester, AdminOperation.CREATE_BUSINESS, fields)
        }
        Then("the DB value cannot be matched from the raw fields without the key: not the unkeyed m1 digest, not another key's digest, no raw value inside") {
            value shouldNotBe AdminCommandExecutor.legacyFingerprintOf(AdminOperation.CREATE_BUSINESS, fields)
            value.removePrefix("h1:") shouldNotBe AdminCommandExecutor.legacyFingerprintOf(AdminOperation.CREATE_BUSINESS, fields).removePrefix("m1:")
            val anotherKey = HmacRequestFingerprintDigester(
                Base64.getEncoder().encodeToString("another-deployment-fingerprint-key-000000000001".toByteArray()), emptyList(), mode,
            )
            value shouldNotBe AdminCommandExecutor.fingerprintOf(anotherKey, AdminOperation.CREATE_BUSINESS, fields)
            listOf(
                command.registrationNumber.value, BusinessFixtures.CANARY_REPRESENTATIVE, BusinessFixtures.CANARY_EMAIL, BusinessFixtures.CANARY_PHONE,
                BusinessFixtures.CANARY_DETAIL_ADDRESS,
            ).forEach { value shouldNotContain it }
            id shouldNotBe null
        }
    }

    Given("② replay / conflict boundary with the keyed fingerprint") {
        Then("same key + same content replays (no new row); same key + other content is IDEMPOTENCY_KEY_REUSED") {
            val k = key()
            val command = create(k)
            val id = succeeded(service.create(command))
            service.create(command.copy(request = command.request.copy(correlationId = "corr-resend"))) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
            service.create(command.copy(displayName = DisplayName("다른 지문 상호"))) shouldBe AdminCommandResult.IdempotencyKeyReused
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", k.value) shouldBe 1L
        }
    }

    Given("③ a pre-HMAC (m1:) row for the same key — Demo-only compatibility comparison (D-MOPS-010 ②, L-25)") {
        Then("Demo: the same content replays the stored result and the stored value is left as it was; other content is 409") {
            val k = key()
            val command = create(k)
            val id = succeeded(service.create(command))
            asLegacyRow(AdminOperation.CREATE_BUSINESS, k, BusinessCommandFingerprintFields.of(command))
            val legacy = stored(AdminOperation.CREATE_BUSINESS, k)
            legacy shouldMatch Regex("^m1:[0-9a-f]{64}$")
            mode.demo = true
            service.create(command.copy(request = command.request.copy(correlationId = "corr-legacy"))) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
            service.create(command.copy(displayName = DisplayName("다른 상호 legacy"))) shouldBe AdminCommandResult.IdempotencyKeyReused
            stored(AdminOperation.CREATE_BUSINESS, k) shouldBe legacy
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", k.value) shouldBe 1L
        }
        Then("Demo: the comparison is not limited to business create — an update (another MASTER admin command) over its m1 row replays too") {
            val id = succeeded(service.create(create()))
            val k = key()
            val update = UpdateBusinessCommand(
                // BIZ-CRIT-APPROVAL-001(§7.1 표 밖 Fixture 변경 — 기대값 불변, Coordinator 확인 대상): 등록 완료 상호 변경은 2인이라 일반정보(사업체 전화번호) 수정으로 같은 명령 종류를 만든다.
                request(k), BusinessId(id), 0, BusinessChangeReason("연락처 정정"), BusinessType.INDIVIDUAL, DisplayName("지문 상호"),
                individualProfile().let { p -> p.copy(registration = p.registration.copy(phoneNumber = com.paycore.master.merchant.domain.PhoneNumber("0233334444"))) },
            )
            val result = service.update(update)
            result shouldBe AdminCommandResult.Succeeded(id, replayed = false)
            asLegacyRow(AdminOperation.UPDATE_BUSINESS, k, BusinessCommandFingerprintFields.of(update))
            service.update(update) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
        }
        Then("LIVE: the comparison is off — the same content over an m1 row is 409 (no replay), nothing written") {
            val k = key()
            val command = create(k)
            succeeded(service.create(command))
            asLegacyRow(AdminOperation.CREATE_BUSINESS, k, BusinessCommandFingerprintFields.of(command))
            val legacy = stored(AdminOperation.CREATE_BUSINESS, k)
            mode.demo = false
            service.create(command) shouldBe AdminCommandResult.IdempotencyKeyReused
            stored(AdminOperation.CREATE_BUSINESS, k) shouldBe legacy
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", k.value) shouldBe 1L
        }
    }
})
