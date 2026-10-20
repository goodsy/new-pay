package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanApprovedCancels
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanNamespace

/**
 * 가상 VAN 취소 원장 기록 Port(append-only — 기관 금융 사실 원본, CANCEL-001 §6.5 `van_cancel_entry`). INSERT만 있고 수정·삭제 연산이 없다(DB 권한 SELECT·INSERT만).
 * TX 없이 호출하면 거부한다. namespace UNIQUE 또는 승인 순번 UNIQUE 충돌은 [DuplicateVanRecord] — 호출자는 새 TX에서 다시 판정한다(CN-22).
 */
interface VanCancelLedgerStore {
    fun find(namespace: VanNamespace): VanCancelEntry?

    /** 한 원 승인(client·environment·account·원 요청 ID)에 승인된 취소의 수 n과 금액 합 Σ — 한 SELECT(한 스냅샷, rev 0.3 m-6). */
    fun approvedTotals(clientId: String, environment: String, institutionAccount: String, originalRequestId: String): VanApprovedCancels

    fun insert(entry: VanCancelEntry)
}
