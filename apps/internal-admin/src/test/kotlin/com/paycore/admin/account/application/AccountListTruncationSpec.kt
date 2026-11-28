package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AccountListPage
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.account.application.required.AdminAccountListReader
import com.paycore.admin.account.application.required.AdminAccountRow
import com.paycore.admin.account.application.required.AdminRoleEventReader
import com.paycore.admin.account.application.required.RoleChangeRow
import com.paycore.admin.account.domain.AdminActorId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/** 계정 목록 상한(S8 리뷰 nit-5): 상한보다 많으면 상한까지만 + `truncated`, 같거나 적으면 전부 + 잘림 없음. Port 대역만(DB 없음). */
class AccountListTruncationSpec : BehaviorSpec({

    val asked = mutableListOf<String>()
    fun service(rows: Int) = AccountQueryService(
        object : AdminSessionQuery {
            override fun current(actorId: String) = AdminSessionView(actorId, "조회자", "ACTIVE", 1, 1, setOf("VIEWER"))
            override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofHours(1))
        },
        object : AdminAccountListReader {
            override fun list(limit: Int, includeLoginName: Boolean) =
                (1..minOf(rows, limit)).map { AdminAccountRow("adm_$it", null, "표시 $it", "ACTIVE", emptyList(), emptyList(), null) }

            // FLOW-2d 입력 보강(Port 메서드 추가): 활성 OPERATOR 대역.
            override fun activeRoleHolders(role: String, limit: Int) =
                (1..minOf(rows, limit)).map { com.paycore.admin.account.application.required.AdminAccountHolderRow("adm_$it", "표시 $it") }.also { asked += "$role:$limit" }
        },
        object : AdminRoleEventReader {
            override fun changes(target: AdminActorId) = emptyList<RoleChangeRow>()
        },
    )

    Given("the account list limit") {
        Then("one row over the limit → the limit only and truncated; exactly the limit → all and not truncated") {
            service(AccountListPage.LIMIT + 1).list("adm_viewer")!!.let { it.accounts.size to it.truncated } shouldBe (AccountListPage.LIMIT to true)
            service(AccountListPage.LIMIT).list("adm_viewer")!!.let { it.accounts.size to it.truncated } shouldBe (AccountListPage.LIMIT to false)
        }
        Then("FLOW-2d active OPERATOR list (D-GOV-024): asks the reader for OPERATOR with one over the limit; over the limit → limit + truncated; display name and account key only") {
            val limit = com.paycore.admin.account.application.provided.ActiveOperatorPage.LIMIT
            asked.clear()
            service(limit + 1).activeOperators().let { it.operators.size to it.truncated } shouldBe (limit to true)
            asked shouldBe listOf("OPERATOR:${limit + 1}")
            service(limit).activeOperators().let { it.operators.size to it.truncated } shouldBe (limit to false)
            service(1).activeOperators().operators.single() shouldBe com.paycore.admin.account.application.provided.ActiveOperatorView("adm_1", "표시 1")
        }
    }
})
