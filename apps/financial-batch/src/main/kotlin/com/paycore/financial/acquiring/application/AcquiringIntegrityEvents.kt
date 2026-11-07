package com.paycore.financial.acquiring.application

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * TX가 rollback돼 DB에 남지 않는 무결성 거절 사건의 프로세스 안 누적 수(ACQ-ADMIN-001 B2 — B1-F 리뷰 n-6). 재매입 실행 TX 끝 상계 무결성 대조(I-17)가 어긋나면 결정·파일 없이
 * 전체 rollback되므로 DB count gauge(`integrity.reacquisition_netting_mismatch` — 커밋된 행 기준)로는 0으로 보인다. 그 거절 횟수를 여기서 세고 지표 adapter가 label 없는
 * counter `paycore.financial.acquiring.reacquisition.integrity_rejected`로 싣는다(ID·금액 없음 — [AcquisitionWaitingBoard]와 같은 모양, 인스턴스별·재기동 시 0부터).
 */
@Component
class AcquiringIntegrityEvents {
    private val reacquisitionRejected = AtomicLong()

    fun reacquisitionIntegrityRejected() {
        reacquisitionRejected.incrementAndGet()
    }

    fun reacquisitionIntegrityRejectedTotal(): Long = reacquisitionRejected.get()
}
