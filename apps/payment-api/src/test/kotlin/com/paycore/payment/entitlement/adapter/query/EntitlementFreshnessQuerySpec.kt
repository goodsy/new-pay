package com.paycore.payment.entitlement.adapter.query

import com.paycore.payment.entitlement.EntitlementDbTest
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.application.required.EntitlementFreshnessReader
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.ZoneId
import java.util.TimeZone
import javax.sql.DataSource

/**
 * 권리 투영 신선도 조회(jOOQ adapter.query)의 시각 비교가 JVM 기본 시간대와 무관한지 확인한다(TZ-FIX-001, ARCHITECTURE-CONVENTIONS §9.1·§13).
 * 저장 `lease_until`은 UTC 벽시계다. 비교 값을 `LocalDateTime`으로 바인딩하면 Asia/Seoul JVM에서 9시간 앞선 시각과 비교하게 된다.
 *
 * 같은 컨텍스트의 다른 시험이 만든 head도 같은 표에 있으므로 이 시험은 같은 `now`에서 자기 head를 넣기 전·후의 **차이**만 판정한다.
 */
@EntitlementDbTest
class EntitlementFreshnessQuerySpec(
    private val context: WebApplicationContext,
    private val reader: EntitlementFreshnessReader,
    private val dataSource: DataSource,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()

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

    Given("a head whose lease expired one hour ago, read on a JVM whose default time zone is Asia/Seoul (TZ-FIX-001)") {
        Then("it counts as expired and adds no lease remaining (the minimum remaining stays positive)") {
            val lease = SnapshotFixture.leaseAt()
            val now = lease.leaseUntil.plus(Duration.ofHours(1))
            withJvmTimeZone("Asia/Seoul") {
                val before = reader.overview(now)
                send(mvc, body(SnapshotFixture.entitled(), lease)).status shouldBe 200
                val after = reader.overview(now)
                listOf(after.heads - before.heads, after.expiredHeads - before.expiredHeads) shouldBe listOf(1L, 1L)
                (after.minLeaseRemainingMillis?.let { it > 0 } ?: true) shouldBe true
            }
        }
    }

    Given("a head whose lease ends one second later, read on an Asia/Seoul JVM (control)") {
        Then("it is not expired and its remaining lease is at most one second") {
            val lease = SnapshotFixture.leaseAt()
            val now = lease.leaseUntil.minus(Duration.ofSeconds(1))
            withJvmTimeZone("Asia/Seoul") {
                val before = reader.overview(now)
                send(mvc, body(SnapshotFixture.entitled(), lease)).status shouldBe 200
                val after = reader.overview(now)
                listOf(after.heads - before.heads, after.expiredHeads - before.expiredHeads) shouldBe listOf(1L, 0L)
                (after.minLeaseRemainingMillis!! in 1L..1_000L) shouldBe true
            }
        }
    }
})
