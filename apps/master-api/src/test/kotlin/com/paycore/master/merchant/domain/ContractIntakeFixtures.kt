package com.paycore.master.merchant.domain

import java.time.Instant
import java.time.LocalDate

/**
 * MERCHANT-FLOW-001 접수 건 Named Test Fixture(Test Fixture Policy — Domain Factory → Named Fixture, src/test 전용, 명세 §4A.15).
 * 정상 Fixture는 공식 경로([ContractIntake.open] → [ContractIntake.transitionTo])로만 상태를 만든다(상태·근거를 독립 random으로 만들지 않는다).
 * FLOW-2a(§4B.6 MINOR-1): 전이 입력(사유·근거)은 Domain이 검사하므로 [inputFor]가 전이마다 필요한 입력을 명시한다 — 계약 완료는 근거 참조([aCompletedIntake]의 `evidenceRef`)와
 * 버전 조건(시작일 = 접수일 KST 오늘, 범위 카드 PG 결제)을 갖는다. 계약 해지 접수 건 Fixture는 없다(D-GOV-018 ②ⓑ — 해지는 `BusinessContractFixtures.aTerminatedContract`).
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

    /** 계약서 수령 근거 참조(시험 값). */
    val evidence = ContractDocumentRef("DOC-2026-11-0001")
    val reason = BusinessChangeReason("서류 보완 요청")

    /** 계약 완료 버전 조건: 시작일 = [t0]의 KST 날짜(2026-11-25 — 오늘 허용), 종료일 없음, 범위 카드 PG 결제(BR-BIZ-330 — 희망 서비스에서 복사하지 않음). */
    fun terms(): BusinessContractTerms = BusinessContractTerms(LocalDate.parse("2026-11-25"), null, setOf(ServiceKind.CARD_PG_PAYMENT))

    /**
     * 전이마다 필요한 입력(§4B.6 MINOR-1): 되돌리기·계약 불가 = 사유 필수, 계약 완료 = 근거 필수 + 버전 조건 + 잠금 뒤 읽은 최신 사업자 계약([latestContract]), 그 밖 = 없음.
     */
    fun inputFor(
        from: ContractIntakeStatus,
        to: ContractIntakeStatus,
        latestContract: BusinessContract? = null,
        evidenceRef: ContractDocumentRef = evidence,
    ): ContractIntakeTransitionInput = when {
        to == ContractIntakeStatus.COMPLETED -> ContractIntakeTransitionInput(document = evidenceRef, completion = ContractIntakeCompletion(terms(), latestContract))
        to == ContractIntakeStatus.REJECTED || (from == ContractIntakeStatus.DOCUMENT_RECEIVED && to == ContractIntakeStatus.CONSULTING) ->
            ContractIntakeTransitionInput(reason = reason)
        else -> ContractIntakeTransitionInput.NONE
    }

    fun aConsultingIntake(
        business: Business = activeBusiness(),
        latest: ContractIntake? = null,
        at: Instant = t0,
        latestContract: BusinessContract? = null,
    ): ContractIntake {
        val kind = if (latestContract?.hasContractAt(at) == true) ContractIntakeKind.CHANGE else ContractIntakeKind.NEW
        return ContractIntake.open(business, latest, latestContract, kind, items(), at, operator).intake
    }

    fun aDocumentReceivedIntake(business: Business = activeBusiness(), latestContract: BusinessContract? = null): ContractIntake =
        aConsultingIntake(business, latestContract = latestContract).move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)

    /** 계약 완료 건(신규 — 계약 없음 → 사업자 계약 + 버전 1). 근거 참조 [evidenceRef]를 명시한다(MINOR-1). */
    fun aCompletedIntake(business: Business = activeBusiness(), evidenceRef: ContractDocumentRef = evidence): ContractIntake =
        aCompletedChange(business, evidenceRef).intake

    /** [aCompletedIntake]의 전체 결과(접수 건 + 전이 이력 + 사업자 계약 변경). */
    fun aCompletedChange(business: Business = activeBusiness(), evidenceRef: ContractDocumentRef = evidence): ContractIntakeChange {
        val received = aDocumentReceivedIntake(business)
        return received.transitionTo(
            business, ContractIntakeStatus.COMPLETED, received.version,
            inputFor(ContractIntakeStatus.DOCUMENT_RECEIVED, ContractIntakeStatus.COMPLETED, null, evidenceRef), t0, operator,
        )
    }

    fun aRejectedIntake(business: Business = activeBusiness()): ContractIntake =
        aConsultingIntake(business).move(business, ContractIntakeStatus.REJECTED)

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
    }

    fun ContractIntake.move(business: Business, target: ContractIntakeStatus, at: Instant = t0, latestContract: BusinessContract? = null): ContractIntake =
        transitionTo(business, target, version, inputFor(status, target, latestContract), at, operator).intake
}
