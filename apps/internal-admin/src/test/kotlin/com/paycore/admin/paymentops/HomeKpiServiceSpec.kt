package com.paycore.admin.paymentops

import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.paymentops.application.HomeKpiService
import com.paycore.admin.paymentops.application.provided.HomeKpiResult
import com.paycore.admin.paymentops.application.required.HomeKpiGateway
import com.paycore.admin.paymentops.application.required.HomeKpiLimits
import com.paycore.admin.paymentops.application.required.HomeKpiMetrics
import com.paycore.admin.paymentops.application.required.PaymentReadResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

/**
 * 운영 홈 KPI Use Case 판정(ADMIN-HOME-KPI-001 §4.2·§6·§8·T-K3-1~4 — 대역 Port, Spring 없음): VIEW 4역할만(UNMASK 단독·역할 없음·비활성 → Forbidden, PAYMENT 미호출), actor별 분당 창
 * (`home-kpi-per-minute`), PAYMENT 불가·NotFound → Unavailable, 결과마다 지표 `home_kpi{result}` 하나(고정 분류).
 */
class HomeKpiServiceSpec : BehaviorSpec({

    class Fixture(roles: Set<String> = setOf("VIEWER"), status: String = "ACTIVE", perMinute: Int = 100, var reply: PaymentReadResult = PaymentReadResult.Found(mapOf("items" to emptyList<Any>()))) {
        val calls = mutableListOf<String>()
        val results = mutableListOf<String>()
        val view = AdminSessionView("adm_01JC00000000000000000000K1", "Viewer", status, 1, 1, roles)
        val service = HomeKpiService(
            object : AdminSessionQuery {
                override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : HomeKpiGateway {
                override fun homeKpis(): PaymentReadResult {
                    calls += "homeKpis"
                    return reply
                }
            },
            object : HomeKpiLimits {
                override val homeKpiPerMinute = perMinute
            },
            object : HomeKpiMetrics {
                override fun result(result: String) {
                    results += result
                }
            },
        )
        val actor get() = view.actorId
    }

    Given("T-K3-1·2 the view roles (C-2)") {
        Then("VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN get the document; UNMASK-only, no role, inactive and unknown accounts are forbidden without a PAYMENT call") {
            listOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                f.service.today(f.actor).shouldBeInstanceOf<HomeKpiResult.Found>().document shouldBe mapOf("items" to emptyList<Any>())
                f.calls shouldBe listOf("homeKpis")
                f.results shouldBe listOf("OK")
            }
            listOf(Fixture(setOf("UNMASK")), Fixture(emptySet()), Fixture(setOf("OPERATOR"), status = "SUSPENDED")).forEach { f ->
                f.service.today(f.actor) shouldBe HomeKpiResult.Forbidden
                f.calls.shouldBeEmpty()
                f.results shouldBe listOf("FORBIDDEN")
            }
            Fixture().let { f ->
                f.service.today("adm_01JC00000000000000000000ZZ") shouldBe HomeKpiResult.Forbidden
                f.calls.shouldBeEmpty()
            }
        }
    }

    Given("T-K3-3 the per-actor minute window") {
        Then("the call after the limit is RateLimited without a PAYMENT call (the minute may roll over once — then a later call is limited)") {
            val f = Fixture(perMinute = 2)
            val outcomes = (1..6).map { f.service.today(f.actor) }
            outcomes.count { it == HomeKpiResult.RateLimited } shouldBe (6 - f.calls.size)
            (outcomes.count { it == HomeKpiResult.RateLimited } >= 2) shouldBe true
            f.results.count { it == "RATE_LIMITED" } shouldBe outcomes.count { it == HomeKpiResult.RateLimited }
        }
    }

    Given("T-K3-4 PAYMENT does not answer usefully") {
        Then("Unavailable and NotFound from the gateway are Unavailable (counted UNAVAILABLE)") {
            Fixture(reply = PaymentReadResult.Unavailable("HTTP_503")).let { f ->
                f.service.today(f.actor) shouldBe HomeKpiResult.Unavailable("HTTP_503")
                f.results shouldBe listOf("UNAVAILABLE")
            }
            Fixture(reply = PaymentReadResult.NotFound).let { f ->
                f.service.today(f.actor).shouldBeInstanceOf<HomeKpiResult.Unavailable>()
                f.results shouldBe listOf("UNAVAILABLE")
            }
        }
    }
})
