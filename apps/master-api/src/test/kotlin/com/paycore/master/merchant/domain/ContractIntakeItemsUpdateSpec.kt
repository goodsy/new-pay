package com.paycore.master.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate

/**
 * MERCHANT-FLOW-001 FLOW-2d(§4A.3 접수 항목 수정 — D-FLOW-007 ④·D-FLOW-009 ①, §6 FLOW-2d 수용 기준 — 순수 Domain):
 * - 열린 건(상담 중·계약서 접수)에서만 접수일·경로·계약 담당자·희망 서비스를 바꾼다. 종결 건(계약 완료·계약 불가·이관)은 `CONTRACT_INTAKE_ITEMS_NOT_EDITABLE`([구현 관례 code]).
 * - 판정 순서(§4B.7): 사업자 생명주기(`BUSINESS_NOT_ACTIVE`) → 기대 version(`CONTRACT_INTAKE_VERSION_CONFLICT`) → 열린 건 → 업무 규칙.
 * - 접수일을 바꾸면 '사유 및 비고' **필수**(`CONTRACT_INTAKE_REASON_REQUIRED`), 미래 날짜는 판정 시각 KST 오늘 기준 거절(`CONTRACT_INTAKE_RECEIVED_ON_FUTURE`). 다른 항목만 바꾸면 사유 선택.
 * - 바뀐 것이 없으면 `CONTRACT_INTAKE_ITEMS_UNCHANGED`([구현 관례 code] — 이력·version을 늘리지 않는다).
 * - 결과: 상태·종류·대상·생성 값 그대로, 접수 항목만 새 값, version + 1, 갱신 시각 = max(판정 시각, 저장 갱신 시각), 갱신자 = 행위자. 전이 이력 행은 만들지 않는다(상태 전이가 아님).
 */
class ContractIntakeItemsUpdateSpec : BehaviorSpec({

    val f = ContractIntakeFixtures
    val at: Instant = f.t0 // 2026-11-25 10:00 KST
    val other = ActorRef("admin:adm_01JCFL0000000000000000OP02")
    val reason = BusinessChangeReason("접수일 정정")

    Given("FLOW-2d an open intake (상담 중·계약서 접수)") {
        Then("channel, manager and desired services change without a reason: items replaced, version + 1, status / kind / target / creation kept") {
            listOf<(Business) -> ContractIntake>({ f.aConsultingIntake(it) }, { f.aDocumentReceivedIntake(it) }).forEach { make ->
                val b = f.activeBusiness()
                val intake = make(b)
                withClue(intake.status) {
                    val next = f.items(channel = ContractIntakeChannel.PHONE, manager = other, desired = setOf(DesiredService.CARD_AUTH, DesiredService.CARD_KEYIN))
                    val updated = intake.updateItems(b, intake.version, next, null, at.plusSeconds(60), other)
                    listOf(updated.items, updated.version, updated.status, updated.kind, updated.targetContractSeq, updated.createdAt, updated.createdBy, updated.updatedBy) shouldBe
                        listOf(next, intake.version + 1, intake.status, intake.kind, intake.targetContractSeq, intake.createdAt, intake.createdBy, other)
                    updated.updatedAt shouldBe at.plusSeconds(60)
                }
            }
        }

        Then("D-FLOW-009 ①: changing the received date needs '사유 및 비고' (CONTRACT_INTAKE_REASON_REQUIRED); with a reason it changes") {
            val b = f.activeBusiness()
            val intake = f.aConsultingIntake(b)
            val earlier = f.items(receivedOn = LocalDate.parse("2026-11-20"))
            shouldThrow<MasterRuleViolation> { intake.updateItems(b, intake.version, earlier, null, at, f.operator) }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED
            intake.updateItems(b, intake.version, earlier, reason, at, f.operator).items!!.receivedOn shouldBe LocalDate.parse("2026-11-20")
        }

        Then("§4A.6 n-4: a received date after the KST today of the decision time is CONTRACT_INTAKE_RECEIVED_ON_FUTURE (checked before the reason); today is allowed") {
            val b = f.activeBusiness()
            val intake = f.aConsultingIntake(b)
            val tomorrow = f.items(receivedOn = LocalDate.parse("2026-11-26"))
            shouldThrow<MasterRuleViolation> { intake.updateItems(b, intake.version, tomorrow, null, at, f.operator) }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_RECEIVED_ON_FUTURE
            shouldThrow<MasterRuleViolation> { intake.updateItems(b, intake.version, tomorrow, reason, at, f.operator) }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_RECEIVED_ON_FUTURE
            // 2026-11-25 15:00Z = 2026-11-26 00:00 KST — 그 시각에는 11-26이 오늘이다(KST 경계).
            intake.updateItems(b, intake.version, tomorrow, reason, Instant.parse("2026-11-25T15:00:00Z"), f.operator).items!!.receivedOn shouldBe LocalDate.parse("2026-11-26")
        }

        Then("nothing changed → CONTRACT_INTAKE_ITEMS_UNCHANGED (even with a reason)") {
            val b = f.activeBusiness()
            val intake = f.aConsultingIntake(b)
            shouldThrow<MasterRuleViolation> { intake.updateItems(b, intake.version, intake.items!!, reason, at, f.operator) }.rejection shouldBe
                MasterRejection.CONTRACT_INTAKE_ITEMS_UNCHANGED
        }

        Then("order: a stale expected version is refused first (CONTRACT_INTAKE_VERSION_CONFLICT); a TERMINATED business before that (BUSINESS_NOT_ACTIVE)") {
            val b = f.activeBusiness()
            val intake = f.aConsultingIntake(b)
            val future = f.items(receivedOn = LocalDate.parse("2026-12-31"))
            shouldThrow<MasterRuleViolation> { intake.updateItems(b, intake.version + 1, future, null, at, f.operator) }.rejection shouldBe
                MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
            val ended = f.terminatedBusiness()
            val endedIntake = ContractIntake.reconstitute(
                ended.id, intake.seq, intake.status, intake.migrated, intake.items, intake.createdAt, intake.createdBy, intake.updatedAt, intake.updatedBy, intake.version,
                intake.kind, intake.targetContractSeq,
            )
            shouldThrow<MasterRuleViolation> { endedIntake.updateItems(ended, intake.version + 1, future, null, at, f.operator) }.rejection shouldBe
                MasterRejection.BUSINESS_NOT_ACTIVE
        }

        Then("the recorded time never goes backwards (max(decision time, stored updatedAt)); another business's intake is a defect (IAE)") {
            val b = f.activeBusiness()
            val intake = f.aConsultingIntake(b, at = at)
            val updated = intake.updateItems(b, intake.version, f.items(channel = ContractIntakeChannel.OTHER), null, at.minusSeconds(3600), f.operator)
            updated.updatedAt shouldBe intake.updatedAt
            shouldThrow<IllegalArgumentException> { intake.updateItems(f.activeBusiness(), intake.version, f.items(channel = ContractIntakeChannel.OTHER), null, at, f.operator) }
        }
    }

    Given("FLOW-2d a closed intake (계약 완료·계약 불가·이관 — D-FLOW-007 ④ 종결 건 수정 불가)") {
        Then("every closed intake refuses with CONTRACT_INTAKE_ITEMS_NOT_EDITABLE (after the version check)") {
            listOf(
                "completed" to { b: Business -> f.aCompletedIntake(b) },
                "rejected" to { b: Business -> f.aRejectedIntake(b) },
                "migrated" to { b: Business -> f.aMigratedCompletedIntake(b) },
            ).forEach { (name, make) ->
                withClue(name) {
                    val b = f.activeBusiness()
                    val closed = make(b)
                    shouldThrow<MasterRuleViolation> { closed.updateItems(b, closed.version, f.items(channel = ContractIntakeChannel.PHONE), reason, at, f.operator) }.rejection shouldBe
                        MasterRejection.CONTRACT_INTAKE_ITEMS_NOT_EDITABLE
                    shouldThrow<MasterRuleViolation> { closed.updateItems(b, closed.version + 1, f.items(channel = ContractIntakeChannel.PHONE), reason, at, f.operator) }.rejection shouldBe
                        MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
                }
            }
        }
    }
})
