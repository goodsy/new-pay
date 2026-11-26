package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.BusinessContractFixtures.kst
import com.paycore.master.merchant.domain.BusinessContractFixtures.midnight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * MERCHANT-FLOW-001 FLOW-2a(§4B.3 시각·기간 규칙, §4B.5 I-3·I-4·I-5·I-7·I-8 — 순수 Domain): 버전 시작일·종료일·범위·닫기.
 * - 시작일 소급 거절(KST 경계표 — 어제 23:59 KST / 오늘 00:00 KST 판정), 오늘 허용(오늘 00:00부터 적용 — MINOR-4).
 * - 종료일 < 시작일 거절, 새 시작 ≤ 직전 시작 거절(이관 시각 포함), 새 시작일 > 직전 종료일 거절·= 허용(BR-BIZ-346 — 공백이 없어도 +1일은 거절), 예정 버전 2개째 거절,
 *   닫기 기록 = 새 시작 시각·닫은 번호 = 닫힌 번호 + 1, 범위 빈 값 거절.
 * - 계약서 수령 근거 참조 [ContractDocumentRef]: `EvidenceRef` 형식 + 카드번호 패턴 거부(§4A.4 m-5 — 고정 code, 값 없음).
 * 근거: BR-BIZ-316·317·329·335·341·343·345·346.
 */
class BusinessContractVersionSpec : BehaviorSpec({

    val f = BusinessContractFixtures
    val operator = f.operator

    Given("BR-BIZ-335 start date (required, no backdating, KST date)") {
        Then("KST boundary table: judged at 23:59 KST the day before, 'today' is still the day before; judged at 00:00 KST today, yesterday is backdating") {
            val business = f.business()
            val day = LocalDate.parse("2026-11-26")
            data class Row(val judgedAt: java.time.Instant, val start: LocalDate, val refused: Boolean)
            listOf(
                Row(kst(day.minusDays(1), 23, 59), day.minusDays(1), false), // 어제 23:59 KST — 그날이 '오늘'
                Row(kst(day.minusDays(1), 23, 59), day, false),
                Row(midnight(day), day.minusDays(1), true), // 오늘 00:00 KST — 어제는 소급
                Row(midnight(day), day, false),
                Row(kst(day, 15), day, false), // 오늘 시작 = 오늘 00:00부터 적용(기록 시각보다 앞섬 — MINOR-4)
                Row(kst(day, 15), day.minusDays(30), true),
            ).forEach { r ->
                withClue("judged ${r.judgedAt}, start ${r.start}") {
                    if (r.refused) {
                        shouldThrow<MasterRuleViolation> { BusinessContract.create(business, null, 1, f.terms(r.start), f.document, null, r.judgedAt, operator) }
                            .rejection shouldBe MasterRejection.BUSINESS_CONTRACT_START_RETROACTIVE
                    } else {
                        val version = checkNotNull(BusinessContract.create(business, null, 1, f.terms(r.start), f.document, null, r.judgedAt, operator).version)
                        listOf(version.startDate, version.startsAt, version.createdAt) shouldBe listOf(r.start, midnight(r.start), r.judgedAt)
                    }
                }
            }
        }

        Then("I-8: a normal version starts at its start date 00:00 KST (stored UTC = start date - 9h); a version with another start time never loads") {
            val v = checkNotNull(BusinessContract.create(f.business(), null, 1, f.terms(f.today), f.document, null, f.t0, operator).version)
            v.startsAt shouldBe java.time.Instant.parse("2026-11-25T15:00:00Z")
            shouldThrow<IllegalArgumentException> {
                BusinessContractVersion(1, 1, false, f.t0, f.today, null, f.cardPg, f.document, null, f.t0, operator)
            }
        }
    }

    Given("BR-BIZ-335·346 end date (optional, ≥ start, valid through the end date)") {
        Then("end < start is BUSINESS_CONTRACT_PERIOD_INVALID; end = start and later ends are accepted; no end = open-ended (never expires)") {
            val business = f.business()
            shouldThrow<MasterRuleViolation> {
                BusinessContract.create(business, null, 1, f.terms(f.today.plusDays(2), f.today.plusDays(1)), f.document, null, f.t0, operator)
            }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID
            listOf(f.today, f.today.plusDays(365)).forEach { end ->
                checkNotNull(BusinessContract.create(business, null, 1, f.terms(f.today, end), f.document, null, f.t0, operator).version).endDate shouldBe end
            }
            checkNotNull(BusinessContract.create(business, null, 1, f.terms(), f.document, null, f.t0, operator).version).expiresAt shouldBe null
        }
    }

    Given("BR-BIZ-329 / I-5 scope (one service kind or more — CARD_PG_PAYMENT only for now)") {
        Then("an empty scope is BUSINESS_CONTRACT_SCOPE_INVALID for a new contract and a new version; a stored version without scope never loads") {
            val business = f.business()
            shouldThrow<MasterRuleViolation> { BusinessContract.create(business, null, 1, f.terms(services = emptySet()), f.document, null, f.t0, operator) }
                .rejection shouldBe MasterRejection.BUSINESS_CONTRACT_SCOPE_INVALID
            shouldThrow<MasterRuleViolation> {
                f.anEffectiveBusinessContract(business).addVersion(business, 2, f.terms(f.today.plusDays(1), services = emptySet()), f.document, null, f.t0, operator)
            }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_SCOPE_INVALID
            shouldThrow<IllegalArgumentException> {
                BusinessContractVersion(1, 1, false, midnight(f.today), f.today, null, emptySet(), f.document, null, f.t0, operator)
            }
        }
    }

    Given("BR-BIZ-317·341·345·346 a new version of the same contract (변경 계약 완료)") {
        Then("the new version closes the previous one at its start: close row = (previous no, new start, previous no + 1), version number + 1, scope / document kept") {
            val business = f.business()
            val start = f.today.minusDays(3)
            val contract = f.anEffectiveBusinessContract(business, start, at = kst(start, 9))
            val change = contract.addVersion(business, 2, f.terms(f.today.plusDays(2)), f.document, f.reason, f.t0, operator)
            val version = checkNotNull(change.version)
            listOf(version.versionNo, version.originIntakeSeq, version.startsAt, version.services, version.document, version.reason, version.migrated) shouldBe
                listOf(2, 2, midnight(f.today.plusDays(2)), f.cardPg, f.document, f.reason, false)
            change.close shouldBe BusinessContractVersionClose(1, midnight(f.today.plusDays(2)), 2, f.t0, operator)
            change.contract.versions.map { it.versionNo } shouldBe listOf(1, 2)
            change.contract.closes shouldBe listOf(checkNotNull(change.close))
            contract.versions.size shouldBe 1 // 원본 불변(새 값만 돌려준다)
        }

        Then("a new start not after the previous start is PERIOD_INVALID (same-day typo cannot be fixed the same day); a migrated version 1 compares by its migration time") {
            val business = f.business()
            val today = f.anEffectiveBusinessContract(business)
            shouldThrow<MasterRuleViolation> { today.addVersion(business, 2, f.terms(f.today), f.document, null, f.t0, operator) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID
            // 이관 시각(오늘 03:00 KST) 시작 버전 뒤 오늘 시작(00:00 KST)은 앞이므로 거절, 내일 시작은 허용.
            val migrated = f.aMigratedContract(business, kst(f.today, 3))
            shouldThrow<MasterRuleViolation> { migrated.addVersion(business, 2, f.terms(f.today), f.document, null, f.t0, operator) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID
            checkNotNull(migrated.addVersion(business, 2, f.terms(f.today.plusDays(1)), f.document, null, f.t0, operator).close).closedAt shouldBe
                midnight(f.today.plusDays(1))
        }

        Then("BR-BIZ-346: with a previous end date e, a new start d' ≤ e is accepted (d' = e: closed at e 00:00, no gap) and d' = e + 1 is refused (not relaxed)") {
            val business = f.business()
            val start = f.today.minusDays(5)
            val end = f.today.plusDays(10)
            val contract = f.anEffectiveBusinessContract(business, start, end, at = kst(start, 9))
            checkNotNull(contract.addVersion(business, 2, f.terms(end), f.document, null, f.t0, operator).close).closedAt shouldBe midnight(end)
            shouldThrow<MasterRuleViolation> { contract.addVersion(business, 2, f.terms(end.plusDays(1)), f.document, null, f.t0, operator) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID
            // 닫힌 직전 버전의 실제 끝 = 닫는 시각(종료일은 닫기로 대체 — I16).
            val two = contract.addVersion(business, 2, f.terms(f.today.plusDays(1)), f.document, null, f.t0, operator).contract
            two.actualEndOf(1) shouldBe midnight(f.today.plusDays(1))
        }

        Then("BR-BIZ-341·345: at most one scheduled version — a second one is BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS; after the scheduled one starts a new one is allowed") {
            val business = f.business()
            val contract = f.anEffectiveBusinessContract(business)
            val scheduled = contract.addVersion(business, 2, f.terms(f.today.plusDays(3)), f.document, null, f.t0, operator).contract
            shouldThrow<MasterRuleViolation> { scheduled.addVersion(business, 3, f.terms(f.today.plusDays(4)), f.document, null, f.t0, operator) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS
            val afterStart = kst(f.today.plusDays(3), 9)
            checkNotNull(scheduled.addVersion(business, 3, f.terms(f.today.plusDays(4)), f.document, null, afterStart, operator).version).versionNo shouldBe 3
        }

        Then("the same origin intake cannot create two versions (I-2 — programming defect, the DB UNIQUE is the second defense)") {
            val business = f.business()
            val contract = f.anEffectiveBusinessContract(business)
            shouldThrow<IllegalArgumentException> { contract.addVersion(business, 1, f.terms(f.today.plusDays(1)), f.document, null, f.t0, operator) }
        }
    }

    Given("I-7 a migrated version (§4B.10 5)") {
        Then("version 1 only, no start date / end date / document / reason; a normal version needs start date and document") {
            val at = kst(f.today, 3)
            BusinessContractVersion(1, 1, true, at, null, null, f.cardPg, null, null, at, f.migrationActor).migrated shouldBe true
            listOf<() -> Any>(
                { BusinessContractVersion(2, 1, true, at, null, null, f.cardPg, null, null, at, f.migrationActor) },
                { BusinessContractVersion(1, 1, true, midnight(f.today), f.today, null, f.cardPg, null, null, at, f.migrationActor) },
                { BusinessContractVersion(1, 1, true, at, null, null, f.cardPg, f.document, null, at, f.migrationActor) },
                { BusinessContractVersion(1, 1, false, midnight(f.today), f.today, null, f.cardPg, null, null, at, operator) },
                { BusinessContractVersion(1, 1, false, midnight(f.today), f.today, f.today.minusDays(1), f.cardPg, f.document, null, at, operator) },
            ).forEachIndexed { i, bad -> withClue("case $i") { shouldThrow<IllegalArgumentException> { bad() } } }
        }
    }

    Given("§4A.4 m-5 ContractDocumentRef (계약서 수령 근거 참조)") {
        Then("1..128 of [A-Za-z0-9._:#/-]; a card-number pattern is refused with the fixed code CARD_NUMBER_PATTERN; errors carry no value") {
            ContractDocumentRef("CONTRACT/2026-11:A#1_v.2").value shouldBe "CONTRACT/2026-11:A#1_v.2"
            ContractDocumentRef.errorOf("") shouldBe BusinessFieldErrorCode.REQUIRED
            ContractDocumentRef.errorOf("x".repeat(129)) shouldBe BusinessFieldErrorCode.TOO_LONG
            ContractDocumentRef.errorOf("bad ref") shouldBe BusinessFieldErrorCode.FORMAT
            ContractDocumentRef.errorOf("계약서") shouldBe BusinessFieldErrorCode.FORMAT
            CardNumberVectors.CARD_LIKE.filter { candidate -> candidate.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._:#/-" } }.forEach { card ->
                withClue("card-like ref") { ContractDocumentRef.errorOf(card) shouldBe BusinessFieldErrorCode.CARD_NUMBER_PATTERN }
            }
            ContractDocumentRef.errorOf("4111-1111-1111-1111") shouldBe BusinessFieldErrorCode.CARD_NUMBER_PATTERN
            ContractDocumentRef.errorOf("RECON-2026-0001") shouldBe null
            val error = shouldThrow<IllegalArgumentException> { ContractDocumentRef("4111111111111111") }
            (error.message ?: "").contains("4111") shouldBe false
            ContractDocumentRef("DOC-1").toString().contains("DOC-1") shouldBe false
        }
    }
})
