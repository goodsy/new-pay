package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.ContractIntakeFixtures.move
import com.paycore.master.merchant.domain.ContractIntakeStatus.COMPLETED
import com.paycore.master.merchant.domain.ContractIntakeStatus.CONSULTING
import com.paycore.master.merchant.domain.ContractIntakeStatus.DOCUMENT_RECEIVED
import com.paycore.master.merchant.domain.ContractIntakeStatus.REJECTED
import com.paycore.master.merchant.domain.ContractIntakeStatus.TERMINATED
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * MERCHANT-FLOW-001 FLOW-1 수용 기준(§6 FLOW-1 — Domain): 전이 표 전체(허용 7종·나머지 거절·같은 상태 거절·종결 비가역·상담 중 → 계약 완료 거절)와 생성 조건
 * (열린 건 있음/완료 건 있음 → 거절, 불가·해지 뒤 → 허용). 근거: §4A.3, D-FLOW-001 ②, D-FLOW-003 ①②, D-FLOW-004 ①②, D-FLOW-005 ①.
 * DB 없이 돈다(순수 Domain — §4A.15). 입력 규칙('사유 및 비고'·근거 참조 필수 여부, 접수일·경로 검사)은 FLOW-2 명령 범위다.
 */
class ContractIntakeSpec : BehaviorSpec({

    val fixtures = ContractIntakeFixtures
    val operator = fixtures.operator

    /** §4A.3 허용 전이(생성 행 제외 6종). 이 표에 없는 전이는 모두 거절이다. */
    val allowed = setOf(
        CONSULTING to DOCUMENT_RECEIVED,
        DOCUMENT_RECEIVED to CONSULTING,
        DOCUMENT_RECEIVED to COMPLETED,
        CONSULTING to REJECTED,
        DOCUMENT_RECEIVED to REJECTED,
        COMPLETED to TERMINATED,
    )

    Given("§4A.3 the transition table") {
        Then("exactly the 7 allowed kinds exist: creation → CONSULTING plus the 6 listed moves (no others)") {
            val moves = ContractIntakeStatus.entries.flatMap { from -> from.allowedTargets.map { from to it } }.toSet()
            moves shouldBe allowed
            (moves.size + 1) shouldBe 7
            val created = ContractIntake.open(fixtures.activeBusiness(), null, fixtures.items(), fixtures.t0, operator)
            listOf(created.intake.status, created.transition.from, created.transition.to) shouldBe listOf(CONSULTING, null, CONSULTING)
        }

        Then("every (from, to) pair of the 5×5 matrix: allowed pairs move (version +1, one transition row), all others — same state included — are refused") {
            ContractIntakeStatus.entries.forEach { from ->
                ContractIntakeStatus.entries.forEach { to ->
                    withClue("$from → $to") {
                        val business = fixtures.activeBusiness()
                        val intake = fixtures.inStatus(from, business)
                        if ((from to to) in allowed) {
                            val change = intake.transitionTo(business, to, intake.version, fixtures.t0.plusSeconds(60), operator)
                            listOf(change.intake.status, change.intake.version, change.intake.seq, change.intake.businessId) shouldBe
                                listOf(to, intake.version + 1, intake.seq, business.id)
                            change.transition shouldBe ContractIntakeTransition(business.id, intake.seq, from, to, false, fixtures.t0.plusSeconds(60), operator)
                        } else {
                            shouldThrow<MasterRuleViolation> { intake.transitionTo(business, to, intake.version, fixtures.t0, operator) }.rejection shouldBe
                                MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
                        }
                    }
                }
            }
        }

        Then("D-FLOW-005 ①: CONSULTING → COMPLETED (skipping 계약서 접수) is refused") {
            val business = fixtures.activeBusiness()
            val consulting = fixtures.aConsultingIntake(business)
            shouldThrow<MasterRuleViolation> { consulting.transitionTo(business, COMPLETED, consulting.version, fixtures.t0, operator) }.rejection shouldBe
                MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
        }

        Then("D-FLOW-001 ②: terminal states never go back — COMPLETED only to TERMINATED, REJECTED and TERMINATED go nowhere") {
            listOf(COMPLETED, REJECTED, TERMINATED).forEach { terminal ->
                val business = fixtures.activeBusiness()
                val intake = fixtures.inStatus(terminal, business)
                (ContractIntakeStatus.entries - setOf(TERMINATED).filter { terminal == COMPLETED }.toSet()).forEach { target ->
                    withClue("$terminal → $target") {
                        shouldThrow<MasterRuleViolation> { intake.transitionTo(business, target, intake.version, fixtures.t0, operator) }.rejection shouldBe
                            MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
                    }
                }
            }
            listOf(CONSULTING, DOCUMENT_RECEIVED).map { it.isOpen } shouldBe listOf(true, true)
            listOf(COMPLETED, REJECTED, TERMINATED).map { it.isOpen } shouldBe listOf(false, false, false)
        }

        Then("a stale expected version is refused before the table (CONTRACT_INTAKE_VERSION_CONFLICT) and the business version is not an input") {
            val business = fixtures.activeBusiness()
            val received = fixtures.aDocumentReceivedIntake(business)
            received.version shouldBe 1
            shouldThrow<MasterRuleViolation> { received.transitionTo(business, COMPLETED, 0, fixtures.t0, operator) }.rejection shouldBe
                MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
            // version이 틀리면 표 밖 전이여도 version 거절이 먼저다(판정 순서).
            shouldThrow<MasterRuleViolation> { received.transitionTo(business, TERMINATED, 0, fixtures.t0, operator) }.rejection shouldBe
                MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
        }

        Then("§4A.3 MINOR-6: a TERMINATED business refuses every intake transition with BUSINESS_NOT_ACTIVE (lifecycle first, before version)") {
            val terminated = fixtures.terminatedBusiness()
            val intake = ContractIntake.reconstitute(
                terminated.id, 1, CONSULTING, false, fixtures.items(), fixtures.t0, operator, fixtures.t0, operator, 0,
            )
            shouldThrow<MasterRuleViolation> { intake.transitionTo(terminated, DOCUMENT_RECEIVED, 99, fixtures.t0, operator) }.rejection shouldBe
                MasterRejection.BUSINESS_NOT_ACTIVE
        }

        Then("the recorded time never goes backwards (max(decision time, stored updatedAt)) — like Business.monotonic") {
            val business = fixtures.activeBusiness()
            val consulting = fixtures.aConsultingIntake(business, at = fixtures.t0.plusSeconds(600))
            val change = consulting.transitionTo(business, DOCUMENT_RECEIVED, 0, fixtures.t0, operator)
            listOf(change.intake.updatedAt, change.transition.at, change.intake.createdAt) shouldBe
                listOf(fixtures.t0.plusSeconds(600), fixtures.t0.plusSeconds(600), fixtures.t0.plusSeconds(600))
        }
    }

    Given("§4A.3 creation conditions (D-FLOW-003 ①, D-FLOW-004 ①)") {
        Then("no intake yet → a new CONSULTING intake: sequence 1, version 0, creation row (none → CONSULTING), items kept") {
            val business = fixtures.activeBusiness()
            val change = ContractIntake.open(business, null, fixtures.items(), fixtures.t0, operator)
            listOf(change.intake.seq, change.intake.version, change.intake.status, change.intake.migrated, change.intake.items) shouldBe
                listOf(1, 0L, CONSULTING, false, fixtures.items())
            change.transition shouldBe ContractIntakeTransition(business.id, 1, null, CONSULTING, false, fixtures.t0, operator)
        }

        Then("an open latest intake (상담 중·계약서 접수) refuses a new intake with CONTRACT_INTAKE_OPEN_EXISTS — open intakes ≤ 1") {
            listOf(CONSULTING, DOCUMENT_RECEIVED).forEach { open ->
                val business = fixtures.activeBusiness()
                shouldThrow<MasterRuleViolation> { ContractIntake.open(business, fixtures.inStatus(open, business), fixtures.items(), fixtures.t0, operator) }
                    .rejection shouldBe MasterRejection.CONTRACT_INTAKE_OPEN_EXISTS
            }
        }

        Then("a COMPLETED latest intake — migrated included — refuses a new intake with CONTRACT_INTAKE_COMPLETED_EXISTS (only after 계약 해지)") {
            val business = fixtures.activeBusiness()
            listOf(fixtures.aCompletedIntake(business), fixtures.aMigratedCompletedIntake(business)).forEach { completed ->
                shouldThrow<MasterRuleViolation> { ContractIntake.open(business, completed, fixtures.items(), fixtures.t0, operator) }.rejection shouldBe
                    MasterRejection.CONTRACT_INTAKE_COMPLETED_EXISTS
            }
        }

        Then("after 계약 불가 or 계약 해지 (migrated intake terminated included) a new intake opens with the next sequence") {
            val business = fixtures.activeBusiness()
            val migratedTerminated = fixtures.aMigratedCompletedIntake(business).move(business, TERMINATED)
            listOf(fixtures.aRejectedIntake(business), fixtures.aTerminatedIntake(business), migratedTerminated).forEach { latest ->
                withClue("after ${latest.status} (migrated=${latest.migrated})") {
                    val change = ContractIntake.open(business, latest, fixtures.items(), fixtures.t0, operator)
                    listOf(change.intake.seq, change.intake.status, change.transition.intakeSeq) shouldBe listOf(latest.seq + 1, CONSULTING, latest.seq + 1)
                }
            }
            // 이관 건(순번 1)이 해지된 뒤 새 접수 건은 순번 2(§4A.9 "이관 뒤 새 접수 건은 순번 2부터").
            ContractIntake.open(business, migratedTerminated, fixtures.items(), fixtures.t0, operator).intake.seq shouldBe 2
        }

        Then("D-FLOW-003 ①·D-FLOW-004 ①: a DRAFT business may open an intake; a TERMINATED business is refused with BUSINESS_NOT_ACTIVE first") {
            listOf(BusinessFixtures.draftMinimal().business, BusinessFixtures.legacyDraft()).forEach { draft ->
                ContractIntake.open(draft, null, fixtures.items(), fixtures.t0, operator).intake.status shouldBe CONSULTING
            }
            val terminated = fixtures.terminatedBusiness()
            val open = ContractIntake.reconstitute(terminated.id, 1, CONSULTING, false, fixtures.items(), fixtures.t0, operator, fixtures.t0, operator, 0)
            shouldThrow<MasterRuleViolation> { ContractIntake.open(terminated, open, fixtures.items(), fixtures.t0, operator) }.rejection shouldBe
                MasterRejection.BUSINESS_NOT_ACTIVE
        }

        Then("blocksNewIntake = open or COMPLETED (the same set as the DB generated UNIQUE key)") {
            ContractIntakeStatus.entries.filter { it.blocksNewIntake } shouldBe listOf(CONSULTING, DOCUMENT_RECEIVED, COMPLETED)
        }
    }

    Given("§4A.9 migrated intake (계약 완료(이관))") {
        Then("it is intake 1, COMPLETED, without intake items, and can only move to TERMINATED (a normal, non-migration transition row)") {
            val business = fixtures.activeBusiness()
            val migrated = fixtures.aMigratedCompletedIntake(business)
            listOf(migrated.seq, migrated.status, migrated.migrated, migrated.items, migrated.createdBy) shouldBe
                listOf(1, COMPLETED, true, null, ActorRef("system:migration"))
            val terminated = migrated.transitionTo(business, TERMINATED, 0, fixtures.t0, operator)
            listOf(terminated.intake.status, terminated.intake.migrated, terminated.transition.migrated, terminated.transition.from) shouldBe
                listOf(TERMINATED, true, false, COMPLETED)
            (ContractIntakeStatus.entries - TERMINATED).forEach { target ->
                shouldThrow<MasterRuleViolation> { migrated.transitionTo(business, target, 0, fixtures.t0, operator) }.rejection shouldBe
                    MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
            }
        }

        Then("invariants: a migrated intake must be sequence 1 in COMPLETED/TERMINATED without items; a non-migrated intake must have items") {
            val id = fixtures.activeBusiness().id
            val at = fixtures.t0
            val bad = listOf(
                { ContractIntake.reconstitute(id, 2, COMPLETED, true, null, at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, CONSULTING, true, null, at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, COMPLETED, true, fixtures.items(), at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, CONSULTING, false, null, at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 0, CONSULTING, false, fixtures.items(), at, operator, at, operator, 0) },
            )
            bad.forEach { shouldThrow<IllegalArgumentException> { it() } }
        }

        Then("transition rows: a creation row opens CONSULTING (COMPLETED only for a migration row of intake 1); a move must be in the table") {
            val id = fixtures.activeBusiness().id
            ContractIntakeTransition(id, 1, null, COMPLETED, true, fixtures.t0, fixtures.migrationActor).migrated shouldBe true
            listOf(
                { ContractIntakeTransition(id, 1, null, COMPLETED, false, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 2, null, COMPLETED, true, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 1, CONSULTING, COMPLETED, false, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 1, COMPLETED, TERMINATED, true, fixtures.t0, operator) },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        }
    }
})
