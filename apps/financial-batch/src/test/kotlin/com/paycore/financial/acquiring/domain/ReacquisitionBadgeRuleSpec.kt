package com.paycore.financial.acquiring.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 재매입 배지 3상태(ACQ-ADMIN-001 §5.1 L-9 배지 규칙·AC9, I-9 — MINOR-6 수용 기준): "재매입 검토 대상" = 거절된(REJECTED·FILE_REJECTED) 레코드 중 어느 세대에도 매입 확정 표지·
 * "재매입 불요" 종결이 없고 더 높은 세대가 없는 것이 하나 이상, "재매입 진행 중" = 그런 레코드는 없고 더 높은 세대가 있는 미확정 레코드가 있음, 새 세대가 ACCEPTED(표지)면 배지 없음.
 * 원 레코드 상태 값(REJECTED)은 그대로다(이력 불변) — 배지는 표시 분류일 뿐이다. Reader SQL(AcquisitionFileViewReaderSpec AC9)이 이 규칙과 같은 값을 낸다.
 */
class ReacquisitionBadgeRuleSpec : BehaviorSpec({

    Given("one record's facts") {
        Then("rejected, latest generation, unresolved → CANDIDATE; a higher generation exists → IN_PROGRESS; accepted in any generation or closed 'not required' → none") {
            listOf(RecordStatus.REJECTED, RecordStatus.FILE_REJECTED).forEach { s ->
                ReacquisitionBadgeRule.recordStage(s, accepted = false, notRequired = false, higherGeneration = false) shouldBe ReacquisitionStage.CANDIDATE
                ReacquisitionBadgeRule.recordStage(s, accepted = false, notRequired = false, higherGeneration = true) shouldBe ReacquisitionStage.IN_PROGRESS
                ReacquisitionBadgeRule.recordStage(s, accepted = true, notRequired = false, higherGeneration = true) shouldBe null
                ReacquisitionBadgeRule.recordStage(s, accepted = false, notRequired = true, higherGeneration = false) shouldBe null
            }
        }
        Then("a record that is not finally rejected never carries a stage (ACCEPTED, AWAITING, UNKNOWN — reconciliation first, CONFLICT)") {
            listOf(RecordStatus.ACCEPTED, RecordStatus.AWAITING, RecordStatus.UNKNOWN, RecordStatus.CONFLICT).forEach { s ->
                ReacquisitionBadgeRule.recordStage(s, accepted = false, notRequired = false, higherGeneration = false) shouldBe null
            }
        }
    }

    Given("a file's records") {
        Then("any CANDIDATE wins, then IN_PROGRESS, else NONE (the badge disappears once the new generation is accepted)") {
            ReacquisitionBadgeRule.fileBadge(listOf(ReacquisitionStage.IN_PROGRESS, ReacquisitionStage.CANDIDATE, null)) shouldBe ReacquisitionBadge.CANDIDATE
            ReacquisitionBadgeRule.fileBadge(listOf(ReacquisitionStage.IN_PROGRESS, null)) shouldBe ReacquisitionBadge.IN_PROGRESS
            ReacquisitionBadgeRule.fileBadge(listOf(null, null)) shouldBe ReacquisitionBadge.NONE
            ReacquisitionBadgeRule.fileBadge(emptyList()) shouldBe ReacquisitionBadge.NONE
        }
        Then("from per-file stage counts (the Reader's aggregate) it is the same rule") {
            ReacquisitionBadgeRule.fileBadge(candidates = 1, inProgress = 3) shouldBe ReacquisitionBadge.CANDIDATE
            ReacquisitionBadgeRule.fileBadge(candidates = 0, inProgress = 1) shouldBe ReacquisitionBadge.IN_PROGRESS
            ReacquisitionBadgeRule.fileBadge(candidates = 0, inProgress = 0) shouldBe ReacquisitionBadge.NONE
        }
    }
})
