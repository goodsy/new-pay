package com.paycore.master.merchant.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 사업자 계약 버전(MERCHANT-FLOW-001 §4B.2·§4B.3 — [BusinessContract] 안 불변 Entity, 표 `business_contract_version` + 범위 표 `business_contract_version_service`, INSERT 전용).
 *
 * - 일반 버전: [startDate](KST 날짜) 필수, [startsAt] = 그 날짜 00:00 KST(I-8 — 저장 UTC = 시작일 − 9시간), [endDate] 선택(≥ 시작일 — 종료일 당일까지 유효,
 *   [expiresAt] = 종료일 다음 날 00:00 KST부터 만료), 계약서 수령 근거 [document] 필수(BR-BIZ-274·336), '사유 및 비고' [reason] 선택.
 * - 이관 버전(§4B.10 5 — BR-BIZ-334·343, I-7): 버전 1만, [startsAt] = 이관 시각, 시작일·종료일·근거·사유 없음.
 * - 범위 [services]는 1개 이상(I-5 — 지금은 [ServiceKind.CARD_PG_PAYMENT]만, `ServiceKind`는 CSA와 함께 쓴다).
 * - 내용은 저장 뒤 바꾸지 않는다(BR-BIZ-329) — 직전 버전은 새 버전이 닫기 기록([BusinessContractVersionClose])으로 닫는다.
 */
@Suppress("LongParameterList")
data class BusinessContractVersion(
    val versionNo: Int,
    val originIntakeSeq: Int,
    val migrated: Boolean,
    val startsAt: Instant,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val services: Set<ServiceKind>,
    val document: ContractDocumentRef?,
    val reason: BusinessChangeReason?,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    init {
        require(versionNo >= 1 && originIntakeSeq >= 1) { "version and origin intake sequences start at 1" }
        require(services.isNotEmpty()) { "a version covers at least one service kind" }
        if (migrated) {
            require(versionNo == 1 && startDate == null && endDate == null && document == null && reason == null) {
                "a migrated version is version 1 without start date, end date, document or reason"
            }
        } else {
            val start = requireNotNull(startDate) { "a version has a start date" }
            require(document != null) { "a version has a contract document reference" }
            require(startsAt == kstMidnight(start)) { "a version starts at its start date 00:00 KST" }
            require(endDate == null || !endDate.isBefore(start)) { "the end date is not before the start date" }
        }
    }

    /** 만료 시각(종료일 다음 날 00:00 KST), 종료일이 없으면 null(만료 없음 — BR-BIZ-346 경계는 명세 §4B.3). */
    val expiresAt: Instant? get() = endDate?.let { kstMidnight(it.plusDays(1)) }

    companion object {
        fun kstMidnight(date: LocalDate): Instant = date.atStartOfDay(SeoulMidnight.ZONE).toInstant()
    }
}
