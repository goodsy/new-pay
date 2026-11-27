package com.paycore.master.merchant.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * MERCHANT-FLOW-001 FLOW-2a 사업자 계약 Named Test Fixture(Test Fixture Policy — Domain Factory → Named Fixture, src/test 전용, 명세 §4B.13).
 * 정상 Fixture는 공식 경로([BusinessContract.create] → [BusinessContract.suspend]·[BusinessContract.terminate]·[BusinessContract.addVersion])로만 상태를 만든다 —
 * 상태·기간을 독립 random으로 만들지 않는다. 이관 계약만 [BusinessContract.reconstitute]로 만든다(실제로도 V21 migration SQL만 만드는 행이고 그 행 모양과 같다 — §4B.10 5).
 * 시각은 판정 시각을 이름에 드러낸다: [today] = 2026-11-26(KST), [t0] = 그날 10:00 KST. 모든 값은 시험 값이다(운영값 아님).
 */
object BusinessContractFixtures {
    val today: LocalDate = LocalDate.parse("2026-11-26")
    val t0: Instant = kst(today, 10)
    val operator = ActorRef("admin:adm_01JCFL0000000000000000OP01")
    val migrationActor = ActorRef("system:migration")
    val document = ContractDocumentRef("CONTRACT/2026-11:A#1_v.2")
    val reason = BusinessChangeReason("계약 조건 변경 요청")
    val cardPg: Set<ServiceKind> = setOf(ServiceKind.CARD_PG_PAYMENT)

    /** KST 날짜 [date]의 [hour]시(UTC Instant). */
    fun kst(date: LocalDate, hour: Int = 0, minute: Int = 0): Instant = date.atTime(hour, minute).atZone(SeoulMidnight.ZONE).toInstant()

    /** KST 날짜 [date]의 00:00 — 일반 버전 시작 시각(§4B.3). */
    fun midnight(date: LocalDate): Instant = kst(date)

    fun terms(start: LocalDate = today, end: LocalDate? = null, services: Set<ServiceKind> = cardPg) = BusinessContractTerms(start, end, services)

    /** 사업자 계약을 둘 사업자(BR-BIZ-332 — DRAFT도 가능하지만 기본은 REGISTERED). */
    fun business(): Business = BusinessFixtures.individualRegistered().business

    /** 신규 계약 완료(접수 건 [originIntakeSeq])로 만든 계약 — 시작일 [start](기본 오늘), 종료일 [end], 기록 시각 [at]. [at]에 유효(시작일이 [at]의 KST 날짜 이하일 때). */
    fun anEffectiveBusinessContract(
        business: Business = business(),
        start: LocalDate = today,
        end: LocalDate? = null,
        at: Instant = t0,
        originIntakeSeq: Int = 1,
    ): BusinessContract = BusinessContract.create(business, null, originIntakeSeq, terms(start, end), document, null, at, operator).contract

    /** 효력 전 계약: 시작일 = [at]의 다음 주(BR-BIZ-327). */
    fun aNotYetEffectiveContract(business: Business = business(), start: LocalDate = today.plusDays(7), at: Instant = t0): BusinessContract =
        anEffectiveBusinessContract(business, start, at = at)

    /** 정지 계약: 유효 계약을 [at] + 1분에 정지. */
    fun aSuspendedContract(business: Business = business(), at: Instant = t0): BusinessContract =
        anEffectiveBusinessContract(business, at = at).suspend(business, 0, reason, at.plus(Duration.ofMinutes(1)), operator).contract

    /** 해지 계약: 유효 계약을 [at] + 1분에 해지(근거 선택 — 없음). */
    fun aTerminatedContract(business: Business = business(), at: Instant = t0): BusinessContract =
        anEffectiveBusinessContract(business, at = at).terminate(business, 0, reason, at.plus(Duration.ofMinutes(1)), operator).contract

    /**
     * 만료 계약: 시작일 [start]·종료일 [endDate]인 계약(시작일 당일에 기록). [endDate] 다음 날 00:00 KST부터 만료 — 판정은 [expiredJudgedAt].
     */
    fun anExpiredContract(business: Business = business(), endDate: LocalDate = today.minusDays(1), start: LocalDate = endDate.minusDays(30)): BusinessContract =
        anEffectiveBusinessContract(business, start, endDate, at = kst(start, 9))

    /** [anExpiredContract]가 만료로 판정되는 시각(종료일 다음 날 00:00 KST 이후). */
    fun expiredJudgedAt(endDate: LocalDate = today.minusDays(1)): Instant = midnight(endDate.plusDays(1)).plusSeconds(1)

    /** §4B.10 5 이관 계약: 순번 1·생성 접수 건 1·이관 표시·버전 1(시작 = 이관 시각, 시작일·종료일·근거 없음, 범위 카드 PG 결제)·`system:migration`. */
    fun aMigratedContract(business: Business = business(), migratedAt: Instant = kst(today, 3)): BusinessContract = BusinessContract.reconstitute(
        BusinessContractId(business.id, 1), 1, true, migratedAt, migrationActor,
        listOf(BusinessContractVersion(1, 1, true, migratedAt, null, null, cardPg, null, null, migratedAt, migrationActor)),
        emptyList(), emptyList(),
    )
}
