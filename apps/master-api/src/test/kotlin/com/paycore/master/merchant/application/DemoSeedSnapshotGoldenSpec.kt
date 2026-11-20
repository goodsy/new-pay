package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.batch.DemoMerchantSeedValues
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.domain.ActorRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.TestConfiguration
import com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * DEMO-SEED-001 I-10(MASTER 쪽)·m7: 운영 Demo 값([DemoMerchantSeedValues] — 등록번호만 공유 시험 DB 중복을 피해 새 값, Snapshot에 없다)으로 18단계 seed를 고정 시계
 * ([MutableMasterClock] 기본 2026-10-09T02:00Z)·고정 ID([ScriptedIdGenerator.enqueue])로 돌린 뒤 최신 Snapshot이 harness가 **앱 코드와 독립으로** 만든
 * `contracts/demo-seed-snapshot-v4.golden.json`과 같은지 본다. contentHash는 MASTER가 자기 정규 바이트로 계산한 SHA-256이라 golden contentHash와 같으면
 * 정규 내용이 바이트 단위로 같다. 같은 파일을 PAYMENT `DemoSeedSnapshotAdmissionSpec`이 실제 Inbox로 적용한다.
 *
 * 운영 host(`demo-shop.paycore.test`)는 Snapshot 내용이고 MASTER는 WEB host 중복을 거절한다(SITE_CHANNEL_DUPLICATE) — 공유 시험 DB에서는 `DemoSeedCommandSpec`이
 * 이미 같은 host를 쓴다. 그래서 [DemoSeedGoldenContext]로 **별도 컨텍스트(새 컨테이너 DB)**를 쓴다.
 */
@MasterDbTest
@Import(DemoSeedGoldenContext::class, DemoSeedBeansTestConfig::class)
class DemoSeedSnapshotGoldenSpec(
    private val seed: DemoMerchantSeedUseCase,
    private val ids: ScriptedIdGenerator,
    private val clock: MutableMasterClock,
    private val container: MySQLContainer,
) : FunSpec({

    // 기대값 변경(MERCHANT-CODE-001 §6·MC-4): v3 → v4 golden(최신 Snapshot에 seed 코드 demoshop01 — revision·권리 내용 기대값은 그대로).
    val golden = JsonMapper.builder().build().readTree(ClassPathResource("contracts/demo-seed-snapshot-v4.golden.json").inputStream)
    fun g(name: String): String = golden.get(name).stringValue()

    beforeSpec {
        ids.reset()
        clock.reset()
    }
    afterAny {
        ids.reset()
        clock.reset()
    }

    test("the latest snapshot after the production Demo seed equals the independently generated golden (content bytes via contentHash, revision, issue count)") {
        ids.enqueue("mer", g("merchantId"))
        ids.enqueue("biz", g("businessId"))
        ids.enqueue("site", g("siteId"))
        ids.enqueue("mid", g("mid"))
        ids.enqueue("asg", g("siteAssignmentId"))
        ids.enqueue("csa", g("agreementId"))
        ids.enqueue("tlp", g("limitPolicyId"))
        ids.enqueue("pas", g("feeAssignmentId"), g("settlementAssignmentId"))
        ids.enqueue("pv", g("feeVersionId"), g("settlementVersionId"))
        ids.enqueue("cra", g("cardRouteId"))
        val result = seed.seed(
            DemoMerchantSeedCommand(
                keyPrefix = "dsg-" + UUID.randomUUID().toString().take(12),
                actor = ActorRef("system:demo-seed"),
                correlationId = "demo-seed-" + UUID.randomUUID().toString().take(13),
                effectiveFrom = Instant.parse(g("effectiveFrom")),
                values = DemoMerchantSeedValues.VALUES.copy(registrationNumber = OnboardingFixture.registrationNumber()),
            ),
        ).shouldBeInstanceOf<DemoMerchantSeedResult.Done>()
        result.merchantAccountId.value shouldBe g("mid")
        val issues = issuesOf(container, result.merchantId)
        issues.size shouldBe 10
        issues.last().revision shouldBe golden.get("revision").longValue()
        issues.last().contentHash shouldBe g("contentHash")
        val payload = latestSnapshot(container, result.merchantId)
        payload.get("contentHash").stringValue() shouldBe g("contentHash")
        payload.get("issuedAt").stringValue() shouldBe g("issuedAt")
        // 읽기 쉬운 차이 보고용: 전송 payload에서 lease·hash 필드를 뺀 내용을 정규화해 golden 정규 문자열과 비교한다(바이트 동일성의 권위는 위 contentHash).
        val mapper = JsonMapper.builder().configure(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).build()
        @Suppress("UNCHECKED_CAST")
        val content = (mapper.convertValue(payload, Map::class.java) as Map<String, Any?>) - setOf("contentHash", "issuedAt", "leaseSeq", "leaseUntil")
        mapper.readTree(mapper.writeValueAsString(content)) shouldBe mapper.readTree(g("contentCanonical"))
    }

    // SITE-CONTRACT-CODE-001 SCODE-1-11·1-12(시험 추가 — 위 시험의 기대값 불변): 새 DB의 운영 Demo seed(가맹점 코드 demoshop01)는 등록 경로로 Site·계약 코드를 받는다
    // (결정적 — site-demoshop01-01·ctr-demoshop01-01). 위 golden contentHash가 그대로라는 사실이 Snapshot에 코드가 없음(Q-SC-7)을 보인다 — 여기서는 payload 문자열로도 확인한다.
    test("SCODE-1-11 the production Demo seed on a new DB issues site-demoshop01-01 and ctr-demoshop01-01; SCODE-1-12 the Snapshot payload has no code") {
        OnboardingFixture.runtime(container).use { c ->
            fun column(sql: String) = c.prepareStatement(sql).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
            column(
                "SELECT k.site_code FROM merchant_site_code k JOIN merchant_code_assignment a ON a.merchant_id = k.merchant_id WHERE a.merchant_code = 'demoshop01' ORDER BY k.code_seq",
            ) shouldBe listOf("site-demoshop01-01")
            column(
                "SELECT k.contract_code FROM contract_code k JOIN merchant_code_assignment a ON a.merchant_id = k.merchant_id WHERE a.merchant_code = 'demoshop01' ORDER BY k.code_seq",
            ) shouldBe listOf("ctr-demoshop01-01")
            val merchantId = column("SELECT merchant_id FROM merchant_code_assignment WHERE merchant_code = 'demoshop01'").single()
            val payload = latestSnapshot(container, com.paycore.master.merchant.domain.MerchantId(merchantId)).toString()
            listOf(payload.contains("site-demoshop01"), payload.contains("ctr-demoshop01"), payload.contains("siteCode"), payload.contains("contractCode")) shouldBe
                listOf(false, false, false, false)
        }
    }
})

/** [DemoSeedSnapshotGoldenSpec] 전용 컨텍스트 표지(빈 없음 — 컨텍스트 cache key를 달리해 새 DB를 받는다). */
// 이 표지는 시험 소스 scan에 걸릴 수 있으므로 아무것도 import하지 않는다(리뷰 m-2 — seed 빈 구성은 Spec의 @Import로만).
@TestConfiguration(proxyBeanMethods = false)
class DemoSeedGoldenContext
