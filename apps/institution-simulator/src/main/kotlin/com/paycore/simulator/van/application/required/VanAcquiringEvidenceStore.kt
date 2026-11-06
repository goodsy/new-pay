package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanLedgerEntry

/**
 * 가상 VAN 원장의 기관 거래 참조 조회 Port(읽기 전용 — 가상 매입사 대조용, ACQUIRING-001 2-E). 원장 기록 Port([VanLedgerStore]·[VanCancelLedgerStore])와 따로 둔다:
 * 기록 Port는 append-only 경로만 갖는다(ArchUnit이 메서드 집합을 본다). 호출자 TX 안에서만(MANDATORY).
 */
interface VanAcquiringEvidenceStore {
    /** client·TEST의 APPROVED 승인 결정 중 기관 거래 참조가 [providerTransactionId]인 것(보통 0~1개). */
    fun approvedAuthorizations(clientId: String, providerTransactionId: String): List<VanLedgerEntry>

    /** client·TEST의 APPROVED 취소 결정 중 취소의 기관 거래 참조가 [providerTransactionId]인 것(보통 0~1개). */
    fun approvedCancellations(clientId: String, providerTransactionId: String): List<VanCancelEntry>
}
