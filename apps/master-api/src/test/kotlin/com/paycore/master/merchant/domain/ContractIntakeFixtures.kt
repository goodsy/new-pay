package com.paycore.master.merchant.domain

import java.time.Instant
import java.time.LocalDate

/**
 * MERCHANT-FLOW-001 접수 건 Named Test Fixture(Test Fixture Policy — Domain Factory → Named Fixture, src/test 전용, 명세 §4A.15).
 * 정상 Fixture는 공식 경로([ContractIntake.open] → [ContractIntake.transitionTo])로만 상태를 만든다(상태·근거를 독립 random으로 만들지 않는다).
 * 이관 건만 [ContractIntake.reconstitute]로 만든다 — 실제로도 migration SQL만 만드는 행이고(§4A.9) 그 행 모양(순번 1·계약 완료·접수 항목 없음·`system:migration`)과 같다.
 * 모든 값은 시험 값이다(운영값 아님).
 */
object ContractIntakeFixtures {
    val t0: Instant = Instant.parse("2026-11-25T01:00:00.000Z")
    val operator = ActorRef("admin:adm_01JCFL0000000000000000OP01")
    val migrationActor = ActorRef("system:migration")

    fun items(
        receivedOn: LocalDate = LocalDate.parse("2026-11-25"),
        channel: ContractIntakeChannel = ContractIntakeChannel.DIRECT_SALES,
        manager: ActorRef = operator,
        desired: Set<DesiredService> = setOf(DesiredService.CARD_AUTH),
    ) = ContractIntakeItems(receivedOn, channel, manager, desired)

    /** 접수 건을 둘 사업자(D-FLOW-003 ① — DRAFT 사업자도 접수 건 생성 가능). */
    fun activeBusiness(): Business = BusinessFixtures.individualRegistered().business

    /** 종료 사업자(종료 명령이 아직 없어 저장 행 복원 — MerchantRegistrationGateSpec과 같은 방식). */
    fun terminatedBusiness(): Business = Business.reconstitute(
        MasterFixture.businessId(), MasterFixture.registrationNumber(), DisplayName("종료 사업자"), BusinessType.INDIVIDUAL, BusinessRegistrationStatus.REGISTERED,
        BusinessVerificationStatus.NOT_CHECKED, Lifecycle.TERMINATED, t0, operator, t0, operator, 0,
    )

    fun aConsultingIntake(business: Business = activeBusiness(), latest: ContractIntake? = null, at: Instant = t0): ContractIntake =
        ContractIntake.open(business, latest, items(), at, operator).intake

    fun aDocumentReceivedIntake(business: Business = activeBusiness()): ContractIntake =
        aConsultingIntake(business).move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)

    fun aCompletedIntake(business: Business = activeBusiness()): ContractIntake =
        aDocumentReceivedIntake(business).move(business, ContractIntakeStatus.COMPLETED)

    fun aRejectedIntake(business: Business = activeBusiness()): ContractIntake =
        aConsultingIntake(business).move(business, ContractIntakeStatus.REJECTED)

    fun aTerminatedIntake(business: Business = activeBusiness()): ContractIntake =
        aCompletedIntake(business).move(business, ContractIntakeStatus.TERMINATED)

    /** §4A.9 이관 건: 순번 1, 계약 완료, 이관 표시, 접수 항목·근거·담당자 없음, 행위자 `system:migration`, version 0. */
    fun aMigratedCompletedIntake(business: Business = activeBusiness(), at: Instant = t0): ContractIntake = ContractIntake.reconstitute(
        business.id, 1, ContractIntakeStatus.COMPLETED, true, null, at, migrationActor, at, migrationActor, 0,
    )

    /** 상태 이름으로 고른 같은 사업자 접수 건(전이 표 전체 시험용). */
    fun inStatus(status: ContractIntakeStatus, business: Business = activeBusiness()): ContractIntake = when (status) {
        ContractIntakeStatus.CONSULTING -> aConsultingIntake(business)
        ContractIntakeStatus.DOCUMENT_RECEIVED -> aDocumentReceivedIntake(business)
        ContractIntakeStatus.COMPLETED -> aCompletedIntake(business)
        ContractIntakeStatus.REJECTED -> aRejectedIntake(business)
        ContractIntakeStatus.TERMINATED -> aTerminatedIntake(business)
    }

    fun ContractIntake.move(business: Business, target: ContractIntakeStatus, at: Instant = t0): ContractIntake =
        transitionTo(business, target, version, at, operator).intake
}
