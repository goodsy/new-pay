package com.paycore.admin.merchantops

import com.paycore.admin.account.application.provided.ActiveOperatorPage
import com.paycore.admin.account.application.provided.ActiveOperatorQuery
import com.paycore.admin.account.application.provided.ActiveOperatorView
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.merchantops.application.ContractManagerQueryService
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import com.paycore.admin.merchantops.application.provided.ContractManagerTarget
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * MERCHANT-FLOW-001 FLOW-2d 재리뷰 MAJOR-1(Coordinator 결정 — 가역 "예 1"): 담당자 목록에 대상 접수 건(사업자·접수 건 순번)을 주면 서버가 **저장된 담당자 계정 키**와 같은 후보에
 * `current = true`를 붙인다(표시명 비교 없음 — 동명이인 오귀속 방지). 응답에 계정 키는 여전히 없다(웹 adapter가 참조로 바꾼다). 대상이 없으면 `current = null`(현행).
 * - 저장된 담당자가 비활성·OPERATOR 아님 → 후보에 없음, current 없음(같은 이름의 활성 운영자에게 붙지 않는다).
 * - 저장된 담당자가 활성 OPERATOR인데 목록이 잘려 빠졌으면 그 후보를 더해 current로 표시(잘려서 빠진 본인 추가와 같은 규칙).
 * - 섹션 없음·읽기 실패·접수 건 없음·이관 건(담당자 없음) → current 없음, 오류 없음(존재 여부를 응답으로 구별하지 않는다).
 * - 조회자는 활성 OPERATOR만(아니면 null — 웹 403, MASTER 섹션을 부르지 않는다).
 */
class ContractManagerQueryServiceSpec : BehaviorSpec({

    val viewer = "adm_01JC00000000000000000000V1"
    val a = "adm_01JC00000000000000000000VA"
    val b = "adm_01JC00000000000000000000VB"
    val biz = "biz_01JC0000000000000000000001"

    class Fixture(viewerRoles: Set<String> = setOf("OPERATOR"), operators: List<ActiveOperatorView>, truncated: Boolean = false) {
        val calls = mutableListOf<String>()
        var section: MasterCallResult = MasterCallResult.NotFound
        val views = mutableMapOf<String, AdminSessionView>()
        val service = ContractManagerQueryService(
            object : AdminSessionQuery {
                override fun current(actorId: String) = views[actorId]
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : ActiveOperatorQuery {
                override fun activeOperators() = ActiveOperatorPage(operators, truncated)
            },
            object : MasterBusinessGateway {
                override fun search(criteria: BusinessSearchCriteria): MasterCallResult = error("not used")
                override fun lookup(registrationNumber: String): MasterCallResult = error("not used")
                override fun detail(businessId: String): MasterCallResult = error("not used")
                override fun contractIntakes(businessId: String): MasterCallResult = section.also { calls += "contract-intakes:$businessId" }
                override fun history(businessId: String, afterSeq: Long): MasterCallResult = error("not used")
                override fun criticalChangeProposal(command: MasterBusinessCommand): MasterBusinessCommandResult = error("not used")
                override fun approvalState(businessId: String): MasterCallResult = error("not used")
                override fun proposalOriginal(businessId: String, proposalId: String): MasterCallResult = error("not used")
                override fun changeEvidence(businessId: String, historyId: Long): MasterCallResult = error("not used")
                override fun command(command: MasterBusinessCommand): MasterBusinessCommandResult = error("not used")
            },
        )

        init {
            views["adm_01JC00000000000000000000V1"] = AdminSessionView("adm_01JC00000000000000000000V1", "운영 담당", "ACTIVE", 1, 1, viewerRoles)
        }
    }

    fun section(vararg intakes: Pair<Int, String?>) = MasterCallResult.Found(
        linkedMapOf("businessId" to biz, "intakes" to intakes.map { (seq, manager) -> linkedMapOf("intakeSeq" to seq.toLong(), "contractManager" to manager, "migrated" to (manager == null)) }),
    )

    Given("no target (현행)") {
        Then("candidates have current = null and MASTER is not called") {
            val f = Fixture(operators = listOf(ActiveOperatorView(viewer, "운영 담당"), ActiveOperatorView(a, "홍길동")))
            val list = f.service.candidates(viewer, null)!!
            list.candidates.map { it.current } shouldBe listOf(null, null)
            f.calls.shouldBeEmpty()
        }
    }

    Given("a target intake") {
        Then("the stored manager (account key) is marked current; no other candidate is") {
            val f = Fixture(operators = listOf(ActiveOperatorView(viewer, "운영 담당"), ActiveOperatorView(a, "홍길동"), ActiveOperatorView(b, "홍길동")))
            f.section = section(2 to "admin:$a", 1 to null)
            f.service.candidates(viewer, ContractManagerTarget(biz, 2))!!.candidates.map { it.actorId to it.current } shouldBe listOf(viewer to false, a to true, b to false)
            f.calls shouldBe listOf("contract-intakes:$biz")
        }

        Then("MAJOR-1 same-name: the stored manager A is no longer active (not listed) and an active B has the same display name → nobody is current") {
            val f = Fixture(operators = listOf(ActiveOperatorView(viewer, "운영 담당"), ActiveOperatorView(b, "홍길동")))
            f.views[a] = AdminSessionView(a, "홍길동", "SUSPENDED", 1, 1, setOf("OPERATOR"))
            f.section = section(2 to "admin:$a")
            f.service.candidates(viewer, ContractManagerTarget(biz, 2))!!.candidates.map { it.actorId to it.current } shouldBe listOf(viewer to false, b to false)
        }

        Then("the stored manager is an active OPERATOR cut off by the limit → added and marked current (display name from the account)") {
            val f = Fixture(operators = listOf(ActiveOperatorView(viewer, "운영 담당")), truncated = true)
            f.views[a] = AdminSessionView(a, "홍길동", "ACTIVE", 1, 1, setOf("OPERATOR"))
            f.section = section(2 to "admin:$a")
            val list = f.service.candidates(viewer, ContractManagerTarget(biz, 2))!!
            list.candidates.map { Triple(it.actorId, it.displayName, it.current) } shouldBe listOf(Triple(viewer, "운영 담당", false), Triple(a, "홍길동", true))
            list.truncated shouldBe true
        }

        Then("no section, an unreadable section, an unknown intake, a migrated intake (no manager) or a system manager → no current, no error") {
            listOf(
                MasterCallResult.NotFound,
                MasterCallResult.Unavailable("TIMEOUT"),
                section(1 to "admin:$a"),
                section(2 to null),
                section(2 to "system:demo-seed"),
            ).forEach { reply ->
                val f = Fixture(operators = listOf(ActiveOperatorView(viewer, "운영 담당"), ActiveOperatorView(a, "홍길동")))
                f.section = reply
                f.service.candidates(viewer, ContractManagerTarget(biz, 2))!!.candidates.map { it.current } shouldBe listOf(false, false)
            }
        }

        Then("a non-OPERATOR viewer → null without calling MASTER") {
            val f = Fixture(viewerRoles = setOf("APPROVER"), operators = listOf(ActiveOperatorView(a, "홍길동")))
            f.service.candidates(viewer, ContractManagerTarget(biz, 2)).shouldBeNull()
            f.calls.shouldBeEmpty()
        }
    }
})
