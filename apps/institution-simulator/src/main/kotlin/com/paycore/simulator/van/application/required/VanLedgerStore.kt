package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanLedgerEntry
import com.paycore.simulator.van.domain.VanNamespace

/**
 * 가상 VAN 원장 기록 Port(append-only — 기관 금융 사실 원본, AUTH-001 §5.5). INSERT만 있고 수정·삭제 연산이 없다(DB 권한도 SELECT·INSERT만 — AU-20). TX 없이 호출하면
 * 거부한다. namespace UNIQUE 충돌(결정과 종결 표지의 동시 INSERT 등)은 [DuplicateVanRecord] — 호출자는 새 TX에서 다시 판정한다.
 */
interface VanLedgerStore {
    fun find(namespace: VanNamespace): VanLedgerEntry?

    fun insert(entry: VanLedgerEntry)
}
