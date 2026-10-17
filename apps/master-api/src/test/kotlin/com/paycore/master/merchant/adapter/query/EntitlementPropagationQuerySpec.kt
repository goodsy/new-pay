package com.paycore.master.merchant.adapter.query

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.required.EntitlementPropagationReader
import com.paycore.master.merchant.application.required.EntitlementTiming
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import javax.sql.DataSource

/**
 * 권리 전파 개요 조회(jOOQ adapter.query)의 lease 비교가 JVM 기본 시간대와 무관한지 확인한다(TZ-FIX-001, MASTER-SNAPSHOT-002 review1 m-4,
 * ARCHITECTURE-CONVENTIONS §9.1·§13). 저장 `lease_until`은 UTC 벽시계다. 비교 값을 `LocalDateTime`으로 바인딩하면 Asia/Seoul JVM에서 9시간 앞선
 * 시각과 비교해, 이미 끝난 이전 lease를 "아직 열림"으로 본다.
 *
 * 같은 컨텍스트의 다른 시험이 만든 Merchant도 같은 표에 있으므로 이 시험은 같은 `now`에서 자기 Merchant를 만들기 전·후의 **차이**만 판정한다.
 * 시험 설정은 주기 실행을 끈다(Relay가 돌지 않으므로 이 Merchant의 최신 revision에는 ACK가 없다).
 */
@MasterDbTest
class EntitlementPropagationQuerySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val reader: EntitlementPropagationReader,
    private val clock: MutableMasterClock,
    private val timing: EntitlementTiming,
    private val dataSource: DataSource,
) : BehaviorSpec({

    afterEach { clock.reset() }

    /** JVM 기본 시간대를 UTC가 아닌 값으로 바꾼 채 실행한다(UTC 호스트에서도 §9.1 회귀를 잡는다 — MerchantStructureQuerySpec과 같은 방식). */
    fun <T> withJvmTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
        dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        try {
            ZoneId.systemDefault() shouldNotBe ZoneId.of("UTC")
            return block()
        } finally {
            TimeZone.setDefault(original)
            dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
        }
    }

    Given("an entitled merchant without any ACK, read on a JVM whose default time zone is Asia/Seoul (TZ-FIX-001)") {
        Then("its latest revision is unconfirmed while an earlier lease is open, and confirmed one hour after every earlier lease ended") {
            clock.reset()
            // 이 Merchant의 모든 revision은 시험 시계(고정)에서 발행되므로 이전 lease의 최댓값은 발행 시각 + lease 기간이다(아래에서 조회로 확인).
            val priorLeaseEnd: Instant = clock.now().plus(timing.leaseDuration)
            val leaseOpen = priorLeaseEnd.minusMillis(1)
            val leaseEnded = priorLeaseEnd.plus(Duration.ofHours(1))
            withJvmTimeZone("Asia/Seoul") {
                val beforeOpen = reader.overview(leaseOpen).unconfirmedLatestRevisions
                val beforeEnded = reader.overview(leaseEnded).unconfirmedLatestRevisions
                val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
                val view = reader.merchant(entitled.merchantId, leaseEnded)!!
                (view.latestRevision > 1L) shouldBe true
                listOf(view.priorLeaseMaxUntil, view.latestAckRevision) shouldBe listOf(priorLeaseEnd, null)

                (reader.overview(leaseOpen).unconfirmedLatestRevisions - beforeOpen) shouldBe 1L
                (reader.overview(leaseEnded).unconfirmedLatestRevisions - beforeEnded) shouldBe 0L
            }
        }
    }
})
