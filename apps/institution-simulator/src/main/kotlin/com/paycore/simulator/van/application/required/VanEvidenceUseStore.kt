package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanNamespace
import java.time.Instant

/**
 * 인증 증거 사용 기록 Port(append-only — AUTH-001 §5.3-3 "다른 요청 ID에 쓰이지 않음"). 증거 하나는 요청 ID 하나에만 쓰인다: PK(evidence_ref)가 다른 요청 ID의 동시 사용을
 * 직렬화한다(충돌은 [DuplicateVanRecord]). TX 없이 호출하면 거부한다.
 */
interface VanEvidenceUseStore {
    /** [evidenceRef]를 쓴 요청의 namespace(없으면 null). 재사용 판정은 namespace 전체로 한다(검토 #2 n-2 — 같은 요청 ID라도 다른 계정이면 다른 요청). */
    fun namespaceOf(evidenceRef: String): VanNamespace?

    fun insert(evidenceRef: String, namespace: VanNamespace, at: Instant)
}
