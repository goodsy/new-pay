package com.paycore.admin.account.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * 원인별 "아직 그 원인으로 정지된" 역할(S8 — Q-S7-2 원인 후보와 `ROLE_REACTIVATE` 실행이 같은 판정): 역할마다 마지막 이벤트가 원인 있는 SUSPEND일 때만.
 * 명시 이벤트 열(Named Fixture — 업무 조건이 보이게)만 쓴다.
 */
class RoleSuspensionsSpec : BehaviorSpec({

    data class Change(override val role: AdminRole, override val type: RoleEventType, override val cause: String? = null) : RoleChange

    fun grant(role: AdminRole) = Change(role, RoleEventType.GRANT)
    fun suspend(role: AdminRole, cause: String) = Change(role, RoleEventType.SUSPEND, cause)

    Given("a reset approval that suspended APPROVER and OPERATOR") {
        Then("both are candidates of that cause; a later recovery episode SUSPEND re-appended on APPROVER moves it to the episode (D-99)") {
            val reset = listOf(grant(AdminRole.APPROVER), grant(AdminRole.OPERATOR), suspend(AdminRole.APPROVER, "apr_R"), suspend(AdminRole.OPERATOR, "apr_R"))
            RoleSuspensions.byCause(reset) shouldBe mapOf("apr_R" to setOf(AdminRole.APPROVER, AdminRole.OPERATOR))
            RoleSuspensions.byCause(reset + suspend(AdminRole.APPROVER, "episode:3")) shouldBe
                mapOf("apr_R" to setOf(AdminRole.OPERATOR), "episode:3" to setOf(AdminRole.APPROVER))
        }
        Then("a role reactivated or revoked afterwards is no longer a candidate; nothing suspended → no cause") {
            val events = listOf(grant(AdminRole.APPROVER), suspend(AdminRole.APPROVER, "apr_R"), grant(AdminRole.VIEWER), suspend(AdminRole.VIEWER, "apr_R"))
            RoleSuspensions.byCause(events + Change(AdminRole.APPROVER, RoleEventType.REACTIVATE, "apr_R") + Change(AdminRole.VIEWER, RoleEventType.REVOKE)).shouldBeEmpty()
            RoleSuspensions.byCause(listOf(grant(AdminRole.VIEWER))).shouldBeEmpty()
        }
    }
})
