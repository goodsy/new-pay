package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.ContractIntakeFixtures.move
import com.paycore.master.merchant.domain.ContractIntakeStatus.COMPLETED
import com.paycore.master.merchant.domain.ContractIntakeStatus.CONSULTING
import com.paycore.master.merchant.domain.ContractIntakeStatus.DOCUMENT_RECEIVED
import com.paycore.master.merchant.domain.ContractIntakeStatus.REJECTED
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * MERCHANT-FLOW-001 FLOW-1 수용 기준(§6 FLOW-1 — Domain): 전이 표 전체·같은 상태 거절·종결 비가역·상담 중 → 계약 완료 거절과 생성 조건.
 * **FLOW-2a 개정(§4B.6·§4B.15 — 기대값 변경은 §4B.15 표의 행과 1:1)**:
 * - 강화(D-GOV-018 ②ⓑ): 계약 완료 → 계약 해지 전이 삭제 — 허용 전이 5종 + 생성, `COMPLETED`·`REJECTED` 모두 끝(어떤 목표든 거절), 이관 건은 순번 1 + `COMPLETED`만.
 * - 허용 확대(D-GOV-014 ①, D-GOV-018 ①ⓔ): 완료 건이 있어도 새 접수 허용 — 종류는 사업자 계약 상태로(계약 있음 → `CHANGE`, 없음·해지·만료 → `NEW`), expectedKind 불일치 거절,
 *   열린 건 있음 거절은 유지.
 * - 추가(MINOR-1·BR-BIZ-324·333·345): 전이 입력(사유·근거) Domain 검사, 계약 완료 효과(신규 = 계약 + 버전 1, 변경 = 새 버전 + 닫기), 변경 계약 완료 거절(대상 해지·만료·예정 있음).
 * 근거: §4A.3, §4B.6, BR-BIZ-318·324·325·333·338·339·345, D-FLOW-001 ②, D-FLOW-003 ①, D-FLOW-005 ①. DB 없이 돈다(순수 Domain — §4A.15).
 */
class ContractIntakeSpec : BehaviorSpec({

    val fixtures = ContractIntakeFixtures
    val operator = fixtures.operator
    val bf = BusinessContractFixtures

    /** §4A.3 허용 전이(생성 행 제외 5종 — [rev 0.4] 계약 완료 → 계약 해지 삭제). 이 표에 없는 전이는 모두 거절이다. */
    val allowed = setOf(
        CONSULTING to DOCUMENT_RECEIVED,
        DOCUMENT_RECEIVED to CONSULTING,
        DOCUMENT_RECEIVED to COMPLETED,
        CONSULTING to REJECTED,
        DOCUMENT_RECEIVED to REJECTED,
    )

    Given("§4A.3 the transition table") {
        Then("exactly the 6 allowed kinds exist: creation → CONSULTING plus the 5 listed moves (no others)") {
            val moves = ContractIntakeStatus.entries.flatMap { from -> from.allowedTargets.map { from to it } }.toSet()
            moves shouldBe allowed
            (moves.size + 1) shouldBe 6
            val created = ContractIntake.open(fixtures.activeBusiness(), null, null, ContractIntakeKind.NEW, fixtures.items(), fixtures.t0, operator)
            listOf(created.intake.status, created.transition.from, created.transition.to) shouldBe listOf(CONSULTING, null, CONSULTING)
        }

        Then("every (from, to) pair of the 4×4 matrix: allowed pairs move (version +1, one transition row), all others — same state included — are refused") {
            ContractIntakeStatus.entries.forEach { from ->
                ContractIntakeStatus.entries.forEach { to ->
                    withClue("$from → $to") {
                        val business = fixtures.activeBusiness()
                        val intake = fixtures.inStatus(from, business)
                        val input = fixtures.inputFor(from, to)
                        if ((from to to) in allowed) {
                            val change = intake.transitionTo(business, to, intake.version, input, fixtures.t0.plusSeconds(60), operator)
                            listOf(change.intake.status, change.intake.version, change.intake.seq, change.intake.businessId) shouldBe
                                listOf(to, intake.version + 1, intake.seq, business.id)
                            change.transition shouldBe
                                ContractIntakeTransition(business.id, intake.seq, from, to, false, fixtures.t0.plusSeconds(60), operator, input.reason, input.document)
                        } else {
                            shouldThrow<MasterRuleViolation> { intake.transitionTo(business, to, intake.version, input, fixtures.t0, operator) }.rejection shouldBe
                                MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
                        }
                    }
                }
            }
        }

        Then("D-FLOW-005 ①: CONSULTING → COMPLETED (skipping 계약서 접수) is refused") {
            val business = fixtures.activeBusiness()
            val consulting = fixtures.aConsultingIntake(business)
            shouldThrow<MasterRuleViolation> {
                consulting.transitionTo(business, COMPLETED, consulting.version, fixtures.inputFor(CONSULTING, COMPLETED), fixtures.t0, operator)
            }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
        }

        // 기대값 변경(§4B.15 ContractIntakeSpec.kt:70-92 — 강화, D-GOV-018 ②ⓑ): COMPLETED도 REJECTED처럼 끝. version 거절 예시는 표 밖 목표(CONSULTING → COMPLETED)로.
        Then("D-FLOW-001 ②·D-GOV-018 ②ⓑ: terminal states never go back — COMPLETED and REJECTED refuse every target (no 계약 해지 transition)") {
            listOf(COMPLETED, REJECTED).forEach { terminal ->
                val business = fixtures.activeBusiness()
                val intake = fixtures.inStatus(terminal, business)
                ContractIntakeStatus.entries.forEach { target ->
                    withClue("$terminal → $target") {
                        shouldThrow<MasterRuleViolation> {
                            intake.transitionTo(business, target, intake.version, fixtures.inputFor(terminal, target), fixtures.t0, operator)
                        }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
                    }
                }
            }
            listOf(COMPLETED, REJECTED).map { it.allowedTargets } shouldBe listOf(emptySet(), emptySet())
            ContractIntakeStatus.entries.map { it.name } shouldBe listOf("CONSULTING", "DOCUMENT_RECEIVED", "COMPLETED", "REJECTED")
            listOf(CONSULTING, DOCUMENT_RECEIVED).map { it.isOpen } shouldBe listOf(true, true)
            listOf(COMPLETED, REJECTED).map { it.isOpen } shouldBe listOf(false, false)
        }

        Then("a stale expected version is refused before the table (CONTRACT_INTAKE_VERSION_CONFLICT) and the business version is not an input") {
            val business = fixtures.activeBusiness()
            val received = fixtures.aDocumentReceivedIntake(business)
            received.version shouldBe 1
            shouldThrow<MasterRuleViolation> {
                received.transitionTo(business, COMPLETED, 0, fixtures.inputFor(DOCUMENT_RECEIVED, COMPLETED), fixtures.t0, operator)
            }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
            // version이 틀리면 표 밖 전이여도 version 거절이 먼저다(판정 순서) — 표 밖 목표 예시: 상담 중 건 → 계약 완료.
            val consulting = fixtures.aConsultingIntake(business)
            shouldThrow<MasterRuleViolation> {
                consulting.transitionTo(business, COMPLETED, 5, fixtures.inputFor(CONSULTING, COMPLETED), fixtures.t0, operator)
            }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
        }

        Then("§4A.3 MINOR-6: a TERMINATED business refuses every intake transition with BUSINESS_NOT_ACTIVE (lifecycle first, before version)") {
            val terminated = fixtures.terminatedBusiness()
            val intake = ContractIntake.reconstitute(
                terminated.id, 1, CONSULTING, false, fixtures.items(), fixtures.t0, operator, fixtures.t0, operator, 0,
            )
            shouldThrow<MasterRuleViolation> {
                intake.transitionTo(terminated, DOCUMENT_RECEIVED, 99, ContractIntakeTransitionInput.NONE, fixtures.t0, operator)
            }.rejection shouldBe MasterRejection.BUSINESS_NOT_ACTIVE
        }

        Then("the recorded time never goes backwards (max(decision time, stored updatedAt)) — like Business.monotonic") {
            val business = fixtures.activeBusiness()
            val consulting = fixtures.aConsultingIntake(business, at = fixtures.t0.plusSeconds(600))
            val change = consulting.transitionTo(business, DOCUMENT_RECEIVED, 0, ContractIntakeTransitionInput.NONE, fixtures.t0, operator)
            listOf(change.intake.updatedAt, change.transition.at, change.intake.createdAt) shouldBe
                listOf(fixtures.t0.plusSeconds(600), fixtures.t0.plusSeconds(600), fixtures.t0.plusSeconds(600))
        }
    }

    Given("§4B.6 MINOR-1 transition input checked in the domain (DB CHECK ck_cint_reason_required / ck_cint_document stays as the second defense)") {
        Then("되돌리기·계약 불가 without '사유 및 비고' → CONTRACT_INTAKE_REASON_REQUIRED; 계약 완료 without a document → CONTRACT_INTAKE_DOCUMENT_REQUIRED") {
            val business = fixtures.activeBusiness()
            val received = fixtures.aDocumentReceivedIntake(business)
            listOf(CONSULTING, REJECTED).forEach { target ->
                shouldThrow<MasterRuleViolation> { received.transitionTo(business, target, received.version, ContractIntakeTransitionInput.NONE, fixtures.t0, operator) }
                    .rejection shouldBe MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED
            }
            val consulting = fixtures.aConsultingIntake(business)
            shouldThrow<MasterRuleViolation> { consulting.transitionTo(business, REJECTED, 0, ContractIntakeTransitionInput.NONE, fixtures.t0, operator) }
                .rejection shouldBe MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED
            val noDocument = ContractIntakeTransitionInput(completion = ContractIntakeCompletion(fixtures.terms(), null))
            shouldThrow<MasterRuleViolation> { received.transitionTo(business, COMPLETED, received.version, noDocument, fixtures.t0, operator) }
                .rejection shouldBe MasterRejection.CONTRACT_INTAKE_DOCUMENT_REQUIRED
        }

        Then("a document on any transition other than 계약 완료 → CONTRACT_INTAKE_DOCUMENT_NOT_ALLOWED; an optional reason is kept on the transition row") {
            val business = fixtures.activeBusiness()
            val consulting = fixtures.aConsultingIntake(business)
            listOf(
                ContractIntakeTransitionInput(document = fixtures.evidence),
                ContractIntakeTransitionInput(reason = fixtures.reason, document = fixtures.evidence),
            ).forEach { input ->
                shouldThrow<MasterRuleViolation> { consulting.transitionTo(business, DOCUMENT_RECEIVED, 0, input, fixtures.t0, operator) }
                    .rejection shouldBe MasterRejection.CONTRACT_INTAKE_DOCUMENT_NOT_ALLOWED
            }
            consulting.transitionTo(business, DOCUMENT_RECEIVED, 0, ContractIntakeTransitionInput(reason = fixtures.reason), fixtures.t0, operator)
                .transition.reason shouldBe fixtures.reason
        }

        Then("transition rows mirror the DB CHECK: document only on a non-migrated → COMPLETED move (required there), reason required on 되돌리기·계약 불가") {
            val id = fixtures.activeBusiness().id
            listOf<() -> Any>(
                { ContractIntakeTransition(id, 1, DOCUMENT_RECEIVED, COMPLETED, false, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 1, CONSULTING, DOCUMENT_RECEIVED, false, fixtures.t0, operator, null, fixtures.evidence) },
                { ContractIntakeTransition(id, 1, CONSULTING, REJECTED, false, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 1, DOCUMENT_RECEIVED, CONSULTING, false, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 1, null, COMPLETED, true, fixtures.t0, fixtures.migrationActor, fixtures.reason) },
            ).forEachIndexed { i, bad -> withClue("case $i") { shouldThrow<IllegalArgumentException> { bad() } } }
        }
    }

    Given("§4A.3·§4B.6 creation conditions (D-FLOW-003 ①, D-GOV-014 ①, BR-BIZ-318·325)") {
        Then("no intake yet → a new CONSULTING intake: sequence 1, version 0, creation row (none → CONSULTING), items kept, kind NEW without a target") {
            val business = fixtures.activeBusiness()
            val change = ContractIntake.open(business, null, null, ContractIntakeKind.NEW, fixtures.items(), fixtures.t0, operator)
            listOf(change.intake.seq, change.intake.version, change.intake.status, change.intake.migrated, change.intake.items) shouldBe
                listOf(1, 0L, CONSULTING, false, fixtures.items())
            listOf(change.intake.kind, change.intake.targetContractSeq) shouldBe listOf(ContractIntakeKind.NEW, null)
            change.transition shouldBe ContractIntakeTransition(business.id, 1, null, CONSULTING, false, fixtures.t0, operator)
        }

        Then("an open latest intake (상담 중·계약서 접수) refuses a new intake with CONTRACT_INTAKE_OPEN_EXISTS — open intakes ≤ 1") {
            listOf(CONSULTING, DOCUMENT_RECEIVED).forEach { open ->
                val business = fixtures.activeBusiness()
                shouldThrow<MasterRuleViolation> {
                    ContractIntake.open(business, fixtures.inStatus(open, business), null, ContractIntakeKind.NEW, fixtures.items(), fixtures.t0, operator)
                }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_OPEN_EXISTS
            }
        }

        // 기대값 변경(§4B.15 ContractIntakeSpec.kt:131-176 — 허용 확대, D-GOV-014 ①·D-GOV-018 ①ⓔ): 완료 건이 있어도 새 접수 허용, 종류는 사업자 계약 상태로.
        Then("BR-BIZ-325 kind judgment: latest contract none / TERMINATED / EXPIRED → NEW; NOT_YET_EFFECTIVE / ACTIVE / SUSPENDED → CHANGE targeting that contract") {
            val at = bf.t0.plus(Duration.ofMinutes(5))
            val business = fixtures.activeBusiness()
            val cases = listOf(
                "none" to null,
                "terminated" to bf.aTerminatedContract(business),
                "expired" to bf.anExpiredContract(business),
                "not yet effective" to bf.aNotYetEffectiveContract(business),
                "active" to bf.anEffectiveBusinessContract(business),
                "suspended" to bf.aSuspendedContract(business),
            )
            cases.map { (name, contract) ->
                withClue(name) {
                    val kind = if (contract?.hasContractAt(at) == true) ContractIntakeKind.CHANGE else ContractIntakeKind.NEW
                    val intake = ContractIntake.open(business, fixtures.aCompletedIntake(business), contract, kind, fixtures.items(), at, operator).intake
                    name to listOf(intake.kind, intake.targetContractSeq)
                }
            } shouldBe listOf(
                "none" to listOf(ContractIntakeKind.NEW, null),
                "terminated" to listOf(ContractIntakeKind.NEW, null),
                "expired" to listOf(ContractIntakeKind.NEW, null),
                "not yet effective" to listOf(ContractIntakeKind.CHANGE, 1),
                "active" to listOf(ContractIntakeKind.CHANGE, 1),
                "suspended" to listOf(ContractIntakeKind.CHANGE, 1),
            )
        }

        Then("a COMPLETED latest intake — migrated included — no longer blocks a new intake: the next sequence opens (CHANGE while the contract exists)") {
            val business = fixtures.activeBusiness()
            val migratedContract = bf.aMigratedContract(business, fixtures.t0.minusSeconds(3600))
            listOf(fixtures.aCompletedIntake(business), fixtures.aMigratedCompletedIntake(business), fixtures.aRejectedIntake(business)).forEach { latest ->
                withClue("after ${latest.status} (migrated=${latest.migrated})") {
                    val change = ContractIntake.open(business, latest, migratedContract, ContractIntakeKind.CHANGE, fixtures.items(), fixtures.t0, operator)
                    listOf(change.intake.seq, change.intake.status, change.transition.intakeSeq, change.intake.kind) shouldBe
                        listOf(latest.seq + 1, CONSULTING, latest.seq + 1, ContractIntakeKind.CHANGE)
                }
            }
            // 이관 건(순번 1) 뒤 새 접수 건은 순번 2(§4A.9 "이관 뒤 새 접수 건은 순번 2부터").
            ContractIntake.open(business, fixtures.aMigratedCompletedIntake(business), migratedContract, ContractIntakeKind.CHANGE, fixtures.items(), fixtures.t0, operator)
                .intake.seq shouldBe 2
        }

        Then("expectedKind ≠ the judged kind (stale screen) → CONTRACT_INTAKE_KIND_MISMATCH; an open intake is refused first") {
            val business = fixtures.activeBusiness()
            val contract = bf.anEffectiveBusinessContract(business)
            val at = bf.t0.plus(Duration.ofMinutes(5))
            shouldThrow<MasterRuleViolation> { ContractIntake.open(business, null, contract, ContractIntakeKind.NEW, fixtures.items(), at, operator) }
                .rejection shouldBe MasterRejection.CONTRACT_INTAKE_KIND_MISMATCH
            shouldThrow<MasterRuleViolation> { ContractIntake.open(business, null, null, ContractIntakeKind.CHANGE, fixtures.items(), at, operator) }
                .rejection shouldBe MasterRejection.CONTRACT_INTAKE_KIND_MISMATCH
            shouldThrow<MasterRuleViolation> {
                ContractIntake.open(business, fixtures.aConsultingIntake(business), contract, ContractIntakeKind.NEW, fixtures.items(), at, operator)
            }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_OPEN_EXISTS
        }

        Then("D-FLOW-003 ①·D-FLOW-004 ①: a DRAFT business may open an intake; a TERMINATED business is refused with BUSINESS_NOT_ACTIVE first") {
            listOf(BusinessFixtures.draftMinimal().business, BusinessFixtures.legacyDraft()).forEach { draft ->
                ContractIntake.open(draft, null, null, ContractIntakeKind.NEW, fixtures.items(), fixtures.t0, operator).intake.status shouldBe CONSULTING
            }
            val terminated = fixtures.terminatedBusiness()
            val open = ContractIntake.reconstitute(terminated.id, 1, CONSULTING, false, fixtures.items(), fixtures.t0, operator, fixtures.t0, operator, 0)
            shouldThrow<MasterRuleViolation> { ContractIntake.open(terminated, open, null, ContractIntakeKind.NEW, fixtures.items(), fixtures.t0, operator) }
                .rejection shouldBe MasterRejection.BUSINESS_NOT_ACTIVE
        }

        Then("only open intakes block a new intake (the same set as the DB generated UNIQUE key uk_cin_open)") {
            ContractIntakeStatus.entries.filter { it.isOpen } shouldBe listOf(CONSULTING, DOCUMENT_RECEIVED)
        }
    }

    Given("BR-BIZ-314·324 계약 완료 effects (same TX: intake + transition + business contract rows)") {
        Then("NEW: a new business contract (seq = latest + 1) with version 1 from the completion input; the transition row carries the document") {
            val business = fixtures.activeBusiness()
            val change = fixtures.aCompletedChange(business)
            val contract = checkNotNull(change.contract)
            listOf(contract.newContract, contract.contract.id, contract.contract.originIntakeSeq, contract.close, contract.event) shouldBe
                listOf(true, BusinessContractId(business.id, 1), change.intake.seq, null, null)
            val version = checkNotNull(contract.version)
            listOf(version.versionNo, version.startDate, version.endDate, version.services, version.document, version.originIntakeSeq) shouldBe
                listOf(1, fixtures.terms().startDate, null, setOf(ServiceKind.CARD_PG_PAYMENT), fixtures.evidence, change.intake.seq)
            change.transition.document shouldBe fixtures.evidence
            // 이전 계약이 해지된 뒤 신규 접수 → 계약 순번 2.
            val terminatedFirst = bf.aTerminatedContract(business, fixtures.t0.minus(Duration.ofDays(3)))
            val received = fixtures.aDocumentReceivedIntake(business)
            received.transitionTo(business, COMPLETED, received.version, fixtures.inputFor(DOCUMENT_RECEIVED, COMPLETED, terminatedFirst), fixtures.t0, operator)
                .contract?.contract?.id?.contractSeq shouldBe 2
        }

        Then("NEW defense (I-1): a contract that exists (계약 있음) at completion → BUSINESS_CONTRACT_EXISTS; the intake itself is not changed") {
            val business = fixtures.activeBusiness()
            val received = fixtures.aDocumentReceivedIntake(business)
            val existing = bf.anEffectiveBusinessContract(business, at = fixtures.t0.minus(Duration.ofDays(1)), start = java.time.LocalDate.parse("2026-11-24"))
            shouldThrow<MasterRuleViolation> {
                received.transitionTo(business, COMPLETED, received.version, fixtures.inputFor(DOCUMENT_RECEIVED, COMPLETED, existing), fixtures.t0, operator)
            }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_EXISTS
            received.status shouldBe DOCUMENT_RECEIVED
        }

        Then("CHANGE: a new version of the target contract + close row of the previous version (BR-BIZ-324); the contract seq is unchanged") {
            val business = fixtures.activeBusiness()
            val contract = bf.anEffectiveBusinessContract(business)
            val at = bf.t0.plus(Duration.ofMinutes(5))
            val received = ContractIntake.open(business, fixtures.aMigratedCompletedIntake(business), contract, ContractIntakeKind.CHANGE, fixtures.items(), at, operator)
                .intake.move(business, DOCUMENT_RECEIVED, at)
            val input = ContractIntakeTransitionInput(
                document = fixtures.evidence, completion = ContractIntakeCompletion(bf.terms(bf.today.plusDays(1)), contract),
            )
            val change = checkNotNull(received.transitionTo(business, COMPLETED, received.version, input, at, operator).contract)
            listOf(change.newContract, change.contract.id.contractSeq, change.version?.versionNo, change.version?.originIntakeSeq, change.close?.versionNo) shouldBe
                listOf(false, 1, 2, received.seq, 1)
        }

        Then("BR-BIZ-333·345·346 CHANGE refusals: target TERMINATED / EXPIRED → BUSINESS_CONTRACT_NOT_CHANGEABLE (intake stays open); scheduled version → SCHEDULED_VERSION_EXISTS") {
            val at = bf.t0.plus(Duration.ofMinutes(5))
            fun receivedChange(business: Business, contract: BusinessContract): ContractIntake =
                // 계약을 만든 접수 건(순번 1) 뒤의 변경 접수 건(순번 2).
                ContractIntake.open(business, fixtures.aMigratedCompletedIntake(business), contract, ContractIntakeKind.CHANGE, fixtures.items(), bf.t0.minusSeconds(1), operator).intake
                    .move(business, DOCUMENT_RECEIVED, bf.t0.minusSeconds(1))

            fun complete(business: Business, intake: ContractIntake, latest: BusinessContract?, judgedAt: java.time.Instant = at) = intake.transitionTo(
                business, COMPLETED, intake.version,
                ContractIntakeTransitionInput(document = fixtures.evidence, completion = ContractIntakeCompletion(bf.terms(bf.today.plusDays(2)), latest)), judgedAt, operator,
            )

            // 접수 건 생성 때는 계약 있음(→ CHANGE), 완료 전에 해지됨.
            val b1 = fixtures.activeBusiness()
            val active1 = bf.anEffectiveBusinessContract(b1, at = bf.t0.minusSeconds(60))
            val intake1 = receivedChange(b1, active1)
            val terminated = active1.terminate(b1, 0, bf.reason, null, bf.t0, operator).contract
            shouldThrow<MasterRuleViolation> { complete(b1, intake1, terminated) }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE
            // 대상 계약이 최신이 아니면(그 뒤 다른 계약이 생김 — 결함 방어) 같은 거절.
            shouldThrow<MasterRuleViolation> { complete(b1, intake1, null) }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE

            // 만료: 종료일 오늘인 계약, 완료 판정은 내일.
            val b2 = fixtures.activeBusiness()
            val ending = bf.anEffectiveBusinessContract(b2, end = bf.today, at = bf.t0.minusSeconds(60))
            val intake2 = receivedChange(b2, ending)
            shouldThrow<MasterRuleViolation> { complete(b2, intake2, ending, bf.kst(bf.today.plusDays(1), 9)) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE

            // 예정 버전 있음(효력 전 계약 포함).
            val b3 = fixtures.activeBusiness()
            val notYet = bf.aNotYetEffectiveContract(b3, at = bf.t0.minusSeconds(60))
            shouldThrow<MasterRuleViolation> { complete(b3, receivedChange(b3, notYet), notYet) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS
            // 거절된 접수 건은 열린 채 남고 계약 불가로만 닫을 수 있다(BR-BIZ-333).
            intake1.status shouldBe DOCUMENT_RECEIVED
            intake1.transitionTo(b1, REJECTED, intake1.version, fixtures.inputFor(DOCUMENT_RECEIVED, REJECTED), at, operator).intake.status shouldBe REJECTED
        }
    }

    Given("§4A.9 migrated intake (계약 완료(이관))") {
        // 기대값 변경(§4B.15 ContractIntakeSpec.kt:342-354 행 — rev 0.4b 추가, 강화, D-GOV-018 ②ⓑ): 이관 건도 COMPLETED 종결 — 해지 전이 없음(어떤 목표든 거절).
        Then("it is intake 1, COMPLETED, kind NEW, without intake items, and refuses every target (no 계약 해지 transition — D-GOV-018 ②ⓑ)") {
            val business = fixtures.activeBusiness()
            val migrated = fixtures.aMigratedCompletedIntake(business)
            listOf(migrated.seq, migrated.status, migrated.migrated, migrated.items, migrated.createdBy, migrated.kind) shouldBe
                listOf(1, COMPLETED, true, null, ActorRef("system:migration"), ContractIntakeKind.NEW)
            ContractIntakeStatus.entries.forEach { target ->
                shouldThrow<MasterRuleViolation> {
                    migrated.transitionTo(business, target, 0, fixtures.inputFor(COMPLETED, target), fixtures.t0, operator)
                }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED
            }
        }

        // 기대값 변경(§4B.15 ContractIntakeSpec.kt:182-202 — 강화): 이관 건은 순번 1 + COMPLETED만(종결 REJECTED 이관 건·CHANGE 이관 건도 위반).
        Then("invariants: a migrated intake must be sequence 1 in COMPLETED (kind NEW) without items; a non-migrated intake must have items; CHANGE ⇔ target") {
            val id = fixtures.activeBusiness().id
            val at = fixtures.t0
            val bad = listOf(
                { ContractIntake.reconstitute(id, 2, COMPLETED, true, null, at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, CONSULTING, true, null, at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, REJECTED, true, null, at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, COMPLETED, true, fixtures.items(), at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, CONSULTING, false, null, at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 0, CONSULTING, false, fixtures.items(), at, operator, at, operator, 0) },
                { ContractIntake.reconstitute(id, 1, COMPLETED, true, null, at, operator, at, operator, 0, ContractIntakeKind.CHANGE, 1) },
                { ContractIntake.reconstitute(id, 2, CONSULTING, false, fixtures.items(), at, operator, at, operator, 0, ContractIntakeKind.CHANGE, null) },
                { ContractIntake.reconstitute(id, 2, CONSULTING, false, fixtures.items(), at, operator, at, operator, 0, ContractIntakeKind.NEW, 1) },
            )
            bad.forEachIndexed { i, it -> withClue("case $i") { shouldThrow<IllegalArgumentException> { it() } } }
        }

        Then("transition rows: a creation row opens CONSULTING (COMPLETED only for a migration row of intake 1); a move must be in the table") {
            val id = fixtures.activeBusiness().id
            ContractIntakeTransition(id, 1, null, COMPLETED, true, fixtures.t0, fixtures.migrationActor).migrated shouldBe true
            listOf(
                { ContractIntakeTransition(id, 1, null, COMPLETED, false, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 2, null, COMPLETED, true, fixtures.t0, operator) },
                { ContractIntakeTransition(id, 1, CONSULTING, COMPLETED, false, fixtures.t0, operator, null, fixtures.evidence) },
                { ContractIntakeTransition(id, 1, COMPLETED, REJECTED, false, fixtures.t0, operator, fixtures.reason) },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        }
    }
})
