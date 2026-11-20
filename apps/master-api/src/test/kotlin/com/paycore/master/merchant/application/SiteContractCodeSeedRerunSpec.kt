package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID

/**
 * SITE-CONTRACT-CODE-001 SCODE-1-11(공유 시험 DB — 시험마다 다른 키 공간·등록번호·host·가맹점 코드): seed는 등록 경로로 코드를 받고(`site-<코드>-01`·`ctr-<코드>-01`),
 * 같은 키 공간 재실행은 요청 지문이 그대로라(코드는 시스템 산출 — 지문 입력 아님) 18단계 모두 재생(충돌·새 행 0), 코드 행 불변.
 * 운영 값(demoshop01) 새 DB의 결정적 코드는 DemoSeedSnapshotGoldenSpec(별도 컨텍스트 새 DB)이 확인한다.
 */
@MasterDbTest
@Import(DemoSeedBeansTestConfig::class)
class SiteContractCodeSeedRerunSpec(
    private val seed: DemoMerchantSeedUseCase,
    private val faults: MasterFaultInjector,
    private val clock: MutableMasterClock,
    private val container: MySQLContainer,
) : FunSpec({

    beforeSpec {
        faults.reset()
        clock.reset()
    }
    afterAny {
        faults.reset()
        clock.reset()
    }

    fun column(sql: String, vararg args: String): List<String> = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    test("SCODE-1-11 seed issues -01 codes through the registration path; re-running the same key space replays every step with unchanged code rows") {
        val code = MerchantCodeFixtures.unique()
        val command = DemoMerchantSeedCommand(
            keyPrefix = "scs-" + UUID.randomUUID().toString().take(12),
            actor = ActorRef("system:demo-seed"),
            correlationId = "demo-seed-" + UUID.randomUUID().toString().take(13),
            effectiveFrom = Instant.parse("2026-10-09T15:00:00Z"),
            values = DemoMerchantSeedValues.VALUES.copy(registrationNumber = OnboardingFixture.registrationNumber(), merchantCode = code, siteHost = OnboardingFixture.host()),
        )
        val first = seed.seed(command).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
        first.replayed shouldBe false
        val rows = {
            column("SELECT CONCAT_WS('|', site_code, source, DATE_FORMAT(assigned_at, '%Y-%m-%d %H:%i:%s.%f')) FROM merchant_site_code WHERE merchant_id = ? ORDER BY code_seq", first.merchantId.value) +
                column("SELECT CONCAT_WS('|', contract_code, source, DATE_FORMAT(assigned_at, '%Y-%m-%d %H:%i:%s.%f')) FROM contract_code WHERE merchant_id = ? ORDER BY code_seq", first.merchantId.value)
        }
        val before = rows()
        before.map { it.substringBefore('|') } shouldBe listOf("site-${code.value}-01", "ctr-${code.value}-01")
        before.map { it.split('|')[1] }.toSet() shouldBe setOf("REGISTRATION")
        val again = seed.seed(command).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
        again.replayed shouldBe true
        again.merchantId shouldBe first.merchantId
        rows() shouldBe before
    }
})
