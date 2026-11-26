package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.BusinessContractFixtures.kst
import com.paycore.master.merchant.domain.BusinessContractFixtures.midnight
import com.paycore.master.merchant.domain.BusinessContractStatus.ACTIVE
import com.paycore.master.merchant.domain.BusinessContractStatus.EXPIRED
import com.paycore.master.merchant.domain.BusinessContractStatus.NOT_YET_EFFECTIVE
import com.paycore.master.merchant.domain.BusinessContractStatus.SUSPENDED
import com.paycore.master.merchant.domain.BusinessContractStatus.TERMINATED
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-FLOW-001 FLOW-2a(§4B.4 상태 계산·전이표, §4B.5 불변식 — 순수 Domain, DB 없이 돈다): 사업자 계약 상태는 저장 열이 아니라 버전 기간·정지/해지 기록·판정 시각으로 계산한다.
 * - 계산 순서 5단계(해지 → 만료 → 정지 → 효력 전 → 유효)와 경계 시각(시작 직전/직후, 종료일 당일 유효·다음 날 00:00 KST 만료).
 * - 전이표 전체(정지·정지 해제·해지·변경 계약 완료 × 효력 전·유효·정지·해지·만료 — 허용·거절 칸), 정지 중 새 버전 → 정지 유지(BR-BIZ-326), 효력 전 정지·해지(BR-BIZ-344),
 *   해지·만료 뒤 모든 명령 거절(BR-BIZ-333·342), 기대 사건 수 불일치 → `BUSINESS_CONTRACT_STATE_CONFLICT`, 종료 사업자 → `BUSINESS_NOT_ACTIVE`(BR-BIZ-332 DRAFT는 허용).
 * 근거: BR-BIZ-313·315·316·317·321·326·333·336·341·342·344·345·346.
 */
class BusinessContractSpec : BehaviorSpec({

    val f = BusinessContractFixtures
    val operator = f.operator
    val later = { at: Instant -> at.plus(Duration.ofMinutes(5)) }

    Given("§4B.4 status calculation order (terminated → expired → suspended → not yet effective → active)") {
        Then("a version starting today is ACTIVE from today 00:00 KST; one starting tomorrow is NOT_YET_EFFECTIVE until tomorrow 00:00 KST (boundary just before / at)") {
            val business = f.business()
            val today = f.anEffectiveBusinessContract(business)
            today.statusAt(f.t0) shouldBe ACTIVE
            today.statusAt(midnight(f.today)) shouldBe ACTIVE
            today.statusAt(midnight(f.today).minusMillis(1)) shouldBe NOT_YET_EFFECTIVE

            val tomorrow = f.anEffectiveBusinessContract(business, start = f.today.plusDays(1))
            tomorrow.statusAt(f.t0) shouldBe NOT_YET_EFFECTIVE
            tomorrow.statusAt(midnight(f.today.plusDays(1)).minusMillis(1)) shouldBe NOT_YET_EFFECTIVE
            tomorrow.statusAt(midnight(f.today.plusDays(1))) shouldBe ACTIVE
        }

        Then("BR-BIZ-346 end boundary: valid through the whole end date (KST), EXPIRED from the next day 00:00 KST") {
            val end = f.today.plusDays(10)
            val contract = f.anEffectiveBusinessContract(f.business(), end = end)
            contract.statusAt(kst(end, 23, 59)) shouldBe ACTIVE
            contract.statusAt(midnight(end.plusDays(1)).minusMillis(1)) shouldBe ACTIVE
            contract.statusAt(midnight(end.plusDays(1))) shouldBe EXPIRED
            contract.versions.single().expiresAt shouldBe midnight(end.plusDays(1))
            // 종료일 = 시작일(하루짜리)도 그날 하루 유효.
            val oneDay = f.anEffectiveBusinessContract(f.business(), end = f.today)
            listOf(oneDay.statusAt(f.t0), oneDay.statusAt(midnight(f.today.plusDays(1)))) shouldBe listOf(ACTIVE, EXPIRED)
        }

        Then("order: TERMINATE wins over expiry and suspension; expiry wins over suspension; suspension wins over not-yet-effective") {
            val business = f.business()
            val end = f.today.plusDays(3)
            val suspended = f.anEffectiveBusinessContract(business, end = end).suspend(business, 0, f.reason, later(f.t0), operator).contract
            suspended.statusAt(later(later(f.t0))) shouldBe SUSPENDED
            suspended.statusAt(midnight(end.plusDays(1))) shouldBe EXPIRED // 만료가 정지보다 앞선다

            val terminated = suspended.terminate(business, 1, f.reason, null, later(later(f.t0)), operator).contract
            terminated.statusAt(later(later(later(f.t0)))) shouldBe TERMINATED
            terminated.statusAt(midnight(end.plusDays(1))) shouldBe TERMINATED // 해지가 만료보다 앞선다

            val notYet = f.aNotYetEffectiveContract(business)
            notYet.suspend(business, 0, f.reason, later(f.t0), operator).contract.statusAt(later(later(f.t0))) shouldBe SUSPENDED
        }

        Then("RESUME ends the suspension: the status is recalculated (ACTIVE, or NOT_YET_EFFECTIVE before version 1 starts) — not a restore of the earlier state") {
            val business = f.business()
            val active = f.aSuspendedContract(business).let { it.resume(business, 1, f.reason, later(later(f.t0)), operator).contract }
            active.statusAt(later(later(later(f.t0)))) shouldBe ACTIVE
            active.events.map { it.kind } shouldBe listOf(BusinessContractEventKind.SUSPEND, BusinessContractEventKind.RESUME)

            val notYet = f.aNotYetEffectiveContract(business)
                .suspend(business, 0, f.reason, later(f.t0), operator).contract
                .resume(business, 1, f.reason, later(later(f.t0)), operator).contract
            notYet.statusAt(later(later(later(f.t0)))) shouldBe NOT_YET_EFFECTIVE
        }

        Then("MAJOR-1 fail-closed: a judgement time earlier than a stored TERMINATE / SUSPEND (clock going backwards) still sees it — never ACTIVE") {
            val business = f.business()
            val suspendAt = later(f.t0)
            val terminateAt = later(suspendAt)
            val suspended = f.anEffectiveBusinessContract(business).suspend(business, 0, f.reason, suspendAt, operator).contract
            val terminated = suspended.terminate(business, 1, f.reason, null, terminateAt, operator).contract
            listOf(f.t0, suspendAt.minusMillis(1), suspendAt).map { suspended.statusAt(it) } shouldBe List(3) { SUSPENDED }
            listOf(f.t0, suspendAt.minusMillis(1), terminateAt.minusMillis(1), terminateAt).map { terminated.statusAt(it) } shouldBe List(4) { TERMINATED }
            listOf(suspended.hasContractAt(f.t0), terminated.hasContractAt(f.t0)) shouldBe listOf(true, false)
            terminated.statusAt(f.t0) shouldBe TERMINATED // 관문(FLOW-3) 판정 `statusAt(lockedNow) = ACTIVE`가 해지 계약을 통과시키지 않는다
            // 사건 시각은 뒤로 가지 않는다: 앞선 판정 시각으로 온 명령은 마지막 사건 시각으로 판정·기록, 거꾸로 된 저장 행은 load하지 않는다.
            checkNotNull(suspended.resume(business, 1, f.reason, f.t0, operator).event).occurredAt shouldBe suspendAt
            shouldThrow<MasterRuleViolation> { terminated.suspend(business, 2, f.reason, f.t0, operator) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED
            shouldThrow<IllegalArgumentException> {
                BusinessContract.reconstitute(
                    suspended.id, 1, false, suspended.createdAt, operator, suspended.versions, emptyList(),
                    listOf(
                        BusinessContractEvent(1, BusinessContractEventKind.SUSPEND, f.reason, null, operator, suspendAt),
                        BusinessContractEvent(2, BusinessContractEventKind.RESUME, f.reason, null, operator, f.t0),
                    ),
                )
            }
        }

        Then("applying version: start ≤ t < actual end (close time or expiry), at most one; a scheduled version is SCHEDULED until its start") {
            val business = f.business()
            val v1Start = f.today.minusDays(10)
            val contract = f.anEffectiveBusinessContract(business, v1Start, at = kst(v1Start, 9))
            val change = contract.addVersion(business, 2, f.terms(f.today.plusDays(5)), f.document, null, f.t0, operator)
            val two = change.contract
            two.applyingVersionAt(f.t0)?.versionNo shouldBe 1
            two.scheduledVersionAt(f.t0)?.versionNo shouldBe 2
            two.phaseOf(1, f.t0) shouldBe BusinessContractVersionPhase.APPLYING
            two.phaseOf(2, f.t0) shouldBe BusinessContractVersionPhase.SCHEDULED
            val switch = midnight(f.today.plusDays(5))
            two.applyingVersionAt(switch.minusMillis(1))?.versionNo shouldBe 1
            two.applyingVersionAt(switch)?.versionNo shouldBe 2
            two.phaseOf(1, switch) shouldBe BusinessContractVersionPhase.ENDED
            two.scheduledVersionAt(switch) shouldBe null
            // 효력 전 계약에는 적용 중 버전이 없다.
            f.aNotYetEffectiveContract(business).applyingVersionAt(f.t0) shouldBe null
        }

        Then("a migrated contract (§4B.10 5) is ACTIVE from the migration time with CARD_PG_PAYMENT in scope") {
            val migratedAt = kst(f.today, 3)
            val migrated = f.aMigratedContract(f.business(), migratedAt)
            migrated.statusAt(migratedAt) shouldBe ACTIVE
            migrated.statusAt(migratedAt.minusMillis(1)) shouldBe NOT_YET_EFFECTIVE
            migrated.applyingVersionAt(f.t0)?.services shouldBe setOf(ServiceKind.CARD_PG_PAYMENT)
        }
    }

    Given("§4B.4 transition table (current × command)") {
        val commands = listOf("SUSPEND", "RESUME", "TERMINATE", "ADD_VERSION")

        /** 상태별 계약과 그 상태로 판정되는 시각. */
        fun contractIn(status: BusinessContractStatus, business: Business): Pair<BusinessContract, Instant> = when (status) {
            NOT_YET_EFFECTIVE -> f.aNotYetEffectiveContract(business) to later(f.t0)
            ACTIVE -> f.anEffectiveBusinessContract(business) to later(f.t0)
            SUSPENDED -> f.aSuspendedContract(business) to later(f.t0)
            TERMINATED -> f.aTerminatedContract(business) to later(f.t0)
            EXPIRED -> f.anExpiredContract(business) to f.t0
        }

        fun run(contract: BusinessContract, business: Business, command: String, at: Instant): BusinessContractChange = when (command) {
            "SUSPEND" -> contract.suspend(business, contract.events.size, f.reason, at, operator)
            "RESUME" -> contract.resume(business, contract.events.size, f.reason, at, operator)
            "TERMINATE" -> contract.terminate(business, contract.events.size, f.reason, null, at, operator)
            else -> contract.addVersion(business, 9, f.terms(f.today.plusDays(1)), f.document, null, at, operator)
        }

        // 기대 결과: 새 상태(판정 시각 직후) 또는 거절 code. 변경 계약 완료(새 버전)는 상태를 바꾸지 않는다(예정 버전 추가).
        val table: Map<Pair<BusinessContractStatus, String>, Any> = mapOf(
            (NOT_YET_EFFECTIVE to "SUSPEND") to SUSPENDED,
            (NOT_YET_EFFECTIVE to "RESUME") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (NOT_YET_EFFECTIVE to "TERMINATE") to TERMINATED,
            (NOT_YET_EFFECTIVE to "ADD_VERSION") to MasterRejection.BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS,
            (ACTIVE to "SUSPEND") to SUSPENDED,
            (ACTIVE to "RESUME") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (ACTIVE to "TERMINATE") to TERMINATED,
            (ACTIVE to "ADD_VERSION") to ACTIVE,
            (SUSPENDED to "SUSPEND") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (SUSPENDED to "RESUME") to ACTIVE,
            (SUSPENDED to "TERMINATE") to TERMINATED,
            (SUSPENDED to "ADD_VERSION") to SUSPENDED,
            (TERMINATED to "SUSPEND") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (TERMINATED to "RESUME") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (TERMINATED to "TERMINATE") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (TERMINATED to "ADD_VERSION") to MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE,
            (EXPIRED to "SUSPEND") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (EXPIRED to "RESUME") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (EXPIRED to "TERMINATE") to MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED,
            (EXPIRED to "ADD_VERSION") to MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE,
        )

        Then("every cell of the 5 × 4 table: allowed cells give the expected status and exactly one new row, refused cells the fixed code") {
            table.size shouldBe BusinessContractStatus.entries.size * commands.size
            BusinessContractStatus.entries.forEach { status ->
                commands.forEach { command ->
                    withClue("$status × $command") {
                        val business = f.business()
                        val (contract, at) = contractIn(status, business)
                        contract.statusAt(at) shouldBe status
                        when (val expected = table.getValue(status to command)) {
                            is MasterRejection -> shouldThrow<MasterRuleViolation> { run(contract, business, command, at) }.rejection shouldBe expected
                            else -> {
                                val change = run(contract, business, command, at)
                                change.contract.statusAt(at.plusMillis(1)) shouldBe expected
                                listOf(change.newContract, change.version != null, change.close != null, change.event != null) shouldBe
                                    if (command == "ADD_VERSION") listOf(false, true, true, false) else listOf(false, false, false, true)
                            }
                        }
                    }
                }
            }
        }

        Then("BR-BIZ-326: a new version while suspended keeps the suspension (the version is added, the status stays SUSPENDED)") {
            val business = f.business()
            val suspended = f.aSuspendedContract(business)
            val change = suspended.addVersion(business, 2, f.terms(f.today.plusDays(1)), f.document, null, later(f.t0), operator)
            change.contract.statusAt(midnight(f.today.plusDays(1))) shouldBe SUSPENDED
            change.contract.applyingVersionAt(midnight(f.today.plusDays(1)))?.versionNo shouldBe 2
        }

        Then("expectedEventCount ≠ the stored event count is BUSINESS_CONTRACT_STATE_CONFLICT before the transition table") {
            val business = f.business()
            val active = f.anEffectiveBusinessContract(business)
            shouldThrow<MasterRuleViolation> { active.terminate(business, 1, f.reason, null, later(f.t0), operator) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_STATE_CONFLICT
            val terminated = f.aTerminatedContract(business)
            shouldThrow<MasterRuleViolation> { terminated.terminate(business, 0, f.reason, null, later(f.t0), operator) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_STATE_CONFLICT
        }

        Then("a TERMINATED business refuses every contract command with BUSINESS_NOT_ACTIVE first; a DRAFT business may create a contract (BR-BIZ-332)") {
            val active = f.business()
            val contract = f.anEffectiveBusinessContract(active)
            val terminatedBusiness = Business.reconstitute(
                active.id, active.registrationNumber, active.displayName, active.businessType, active.registrationStatus, active.verificationStatus, Lifecycle.TERMINATED,
                f.t0, operator, f.t0, operator, 0,
            )
            listOf<() -> Unit>(
                { contract.suspend(terminatedBusiness, 99, f.reason, later(f.t0), operator) },
                { contract.resume(terminatedBusiness, 99, f.reason, later(f.t0), operator) },
                { contract.terminate(terminatedBusiness, 99, f.reason, null, later(f.t0), operator) },
                { contract.addVersion(terminatedBusiness, 2, f.terms(f.today.plusDays(1)), f.document, null, later(f.t0), operator) },
                { BusinessContract.create(terminatedBusiness, null, 1, f.terms(), f.document, null, f.t0, operator) },
            ).forEach { shouldThrow<MasterRuleViolation> { it() }.rejection shouldBe MasterRejection.BUSINESS_NOT_ACTIVE }
            val draft = BusinessFixtures.draftMinimal().business
            BusinessContract.create(draft, null, 1, f.terms(), f.document, null, f.t0, operator).contract.statusAt(f.t0) shouldBe ACTIVE
        }

        Then("events: reason required (BusinessChangeReason), evidence only on TERMINATE (Q-FLOW-38 — suspend/resume take no evidence until answered); sequence 1, 2, …") {
            val business = f.business()
            val change = f.anEffectiveBusinessContract(business).terminate(business, 0, f.reason, f.document, later(f.t0), operator)
            val event = checkNotNull(change.event)
            listOf(event.eventSeq, event.kind, event.reason, event.evidence, event.actor, event.occurredAt) shouldBe
                listOf(1, BusinessContractEventKind.TERMINATE, f.reason, f.document, operator, later(f.t0))
            shouldThrow<IllegalArgumentException> { BusinessContractEvent(1, BusinessContractEventKind.SUSPEND, f.reason, f.document, operator, f.t0) }
            shouldThrow<IllegalArgumentException> { BusinessContractEvent(0, BusinessContractEventKind.SUSPEND, f.reason, null, operator, f.t0) }
        }
    }

    Given("BR-BIZ-315 / I-1 creation of a new contract (신규 계약 완료)") {
        Then("no latest, or latest TERMINATED / EXPIRED → contract seq = latest + 1 with version 1; latest NOT_YET_EFFECTIVE / ACTIVE / SUSPENDED → BUSINESS_CONTRACT_EXISTS") {
            val business = f.business()
            val first = BusinessContract.create(business, null, 1, f.terms(), f.document, f.reason, f.t0, operator)
            listOf(first.newContract, first.contract.id, first.version?.versionNo, first.close, first.event) shouldBe
                listOf(true, BusinessContractId(business.id, 1), 1, null, null)
            listOf(first.contract.originIntakeSeq, first.contract.migrated, first.contract.createdAt, first.contract.createdBy) shouldBe listOf(1, false, f.t0, operator)
            first.version?.reason shouldBe f.reason

            listOf(f.aTerminatedContract(business) to later(f.t0), f.anExpiredContract(business) to f.t0).forEach { (latest, at) ->
                BusinessContract.create(business, latest, 3, f.terms(at.atZone(SeoulMidnight.ZONE).toLocalDate()), f.document, null, at, operator)
                    .contract.id.contractSeq shouldBe 2
            }
            listOf(f.aNotYetEffectiveContract(business), f.anEffectiveBusinessContract(business), f.aSuspendedContract(business)).forEach { latest ->
                shouldThrow<MasterRuleViolation> { BusinessContract.create(business, latest, 2, f.terms(), f.document, null, later(f.t0), operator) }
                    .rejection shouldBe MasterRejection.BUSINESS_CONTRACT_EXISTS
            }
        }
    }

    Given("§4B.5 invariants on reconstitution (stored rows that break a rule never load)") {
        Then("versions 1..n contiguous, one close per earlier version at the next start, starts strictly increasing, a migrated contract is seq 1 with a migrated version 1, TERMINATE last") {
            val business = f.business()
            val id = BusinessContractId(business.id, 1)
            val v1 = BusinessContractVersion(1, 1, false, midnight(f.today), f.today, null, f.cardPg, f.document, null, f.t0, operator)
            val v2 = BusinessContractVersion(2, 2, false, midnight(f.today.plusDays(1)), f.today.plusDays(1), null, f.cardPg, f.document, null, f.t0, operator)
            val close = BusinessContractVersionClose(1, midnight(f.today.plusDays(1)), 2, f.t0, operator)
            BusinessContract.reconstitute(id, 1, false, f.t0, operator, listOf(v1, v2), listOf(close), emptyList()).versions.size shouldBe 2
            val terminate = BusinessContractEvent(1, BusinessContractEventKind.TERMINATE, f.reason, null, operator, f.t0)
            val suspend = BusinessContractEvent(2, BusinessContractEventKind.SUSPEND, f.reason, null, operator, f.t0)
            listOf<() -> Any>(
                { BusinessContract.reconstitute(id, 1, false, f.t0, operator, emptyList(), emptyList(), emptyList()) },
                { BusinessContract.reconstitute(id, 1, false, f.t0, operator, listOf(v2), emptyList(), emptyList()) },
                { BusinessContract.reconstitute(id, 1, false, f.t0, operator, listOf(v1, v2), emptyList(), emptyList()) },
                { BusinessContract.reconstitute(id, 1, false, f.t0, operator, listOf(v1, v2), listOf(close.copy(closedAt = f.t0)), emptyList()) },
                { BusinessContract.reconstitute(id, 1, false, f.t0, operator, listOf(v2.copy(versionNo = 1), v1.copy(versionNo = 2)), listOf(close), emptyList()) },
                { BusinessContract.reconstitute(id, 2, false, f.t0, operator, listOf(v1), emptyList(), emptyList()) },
                { BusinessContract.reconstitute(BusinessContractId(business.id, 2), 1, true, f.t0, operator, listOf(v1), emptyList(), emptyList()) },
                { BusinessContract.reconstitute(id, 1, true, f.t0, operator, listOf(v1), emptyList(), emptyList()) },
                { BusinessContract.reconstitute(id, 1, false, f.t0, operator, listOf(v1), emptyList(), listOf(terminate, suspend)) },
                { BusinessContract.reconstitute(id, 1, false, f.t0, operator, listOf(v1), emptyList(), listOf(suspend)) },
            ).forEachIndexed { i, bad -> withClue("case $i") { shouldThrow<IllegalArgumentException> { bad() } } }
            shouldThrow<IllegalArgumentException> { BusinessContractVersionClose(1, f.t0, 3, f.t0, operator) }
            shouldThrow<IllegalArgumentException> { BusinessContractId(business.id, 0) }
        }

        Then("toString carries no document reference or reason text") {
            val contract = f.anEffectiveBusinessContract(f.business())
            val text = contract.toString() + contract.versions.toString()
            listOf(f.document.value, f.reason.value).filter { text.contains(it) } shouldBe emptyList()
        }
    }
})
