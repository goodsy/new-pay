package com.paycore.admin.support

import com.paycore.admin.account.application.provided.OneShotResult
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe

/**
 * Kotest 동등 비교 실측(S8 리뷰 — TEST-ASSERT-001 입력, internal-admin 범위). 각 행은 "이 단언이 **통과하는가**"를 기록한다(통과 = 실제로는 다른 값인데도 통과하면 그 꼴은 vacuous).
 * Kotest 버전을 올렸을 때 동작이 바뀌면 이 시험이 알려 준다 — 그때 이 표와 명세 §25.11을 함께 고친다.
 * TEST-ASSERT-001: 공유 ProjectConfig가 `kotest.assertions.show-data-class-diffs=false`로 공허 경로를 끈다. 아래 실측은 그 속성을
 * Kotest 기본값(true)으로 잠시 되돌려 잰다([passes]). 저장소 전체 실측표는 libs/test-support `KotestEqualityProbeSpec`.
 */
class KotestEqualityProbeSpec : FunSpec({

    /** Kotest 기본값(show-data-class-diffs=true)에서 [assertion]이 통과하는지 잰다. 속성은 끝나면 원래 값으로 되돌린다. */
    fun passes(assertion: () -> Unit): Boolean {
        val key = "kotest.assertions.show-data-class-diffs"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        try {
            return runCatching(assertion).isSuccess
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    test("measured: Pair(data class field) vs data object expected is VACUOUS — passes although the values differ") {
        passes { (true to (OneShotResult.Refused("X") as OneShotResult)) shouldBe (true to OneShotResult.Lost) } shouldBe true
    }

    test("measured: direct comparison and List of results are strict") {
        passes { (OneShotResult.Refused("X") as OneShotResult) shouldBe OneShotResult.Lost } shouldBe false
        passes { listOf<OneShotResult>(OneShotResult.Refused("X")) shouldBe listOf(OneShotResult.Lost) } shouldBe false
    }

    test("measured: sealed subtypes with the same property name and value (Refused(code) vs Rejected(code)) are strict, directly and inside a Pair") {
        passes { (ApprovalOutcome.Refused("A") as ApprovalOutcome) shouldBe ApprovalOutcome.Rejected("A") } shouldBe false
        passes { (true to (ApprovalOutcome.Refused("A") as ApprovalOutcome)) shouldBe (true to ApprovalOutcome.Rejected("A")) } shouldBe false
    }

    test("measured: shouldBeIn on Pairs (ApprovalRaceSpec form) is strict for data objects") {
        passes { ((ApprovalOutcome.AlreadyDecided as Any) to (0 as Any)) shouldBeIn listOf<Pair<Any, Any>>(ApprovalOutcome.Expired to 0, ApprovalOutcome.AlreadyDecided to 1) } shouldBe false
        passes { ((ApprovalOutcome.Expired as Any) to (0 as Any)) shouldBeIn listOf<Pair<Any, Any>>(ApprovalOutcome.Expired to 0, ApprovalOutcome.AlreadyDecided to 1) } shouldBe true
    }

    test("guard (TEST-ASSERT-001): under the shared ProjectConfig the Pair/data object shape fails in this module") {
        runCatching { (true to (OneShotResult.Refused("X") as OneShotResult)) shouldBe (true to OneShotResult.Lost) }.isSuccess shouldBe false
    }
})
