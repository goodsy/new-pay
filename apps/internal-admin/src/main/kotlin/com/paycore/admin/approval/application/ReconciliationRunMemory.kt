package com.paycore.admin.approval.application

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * 이 인스턴스의 마지막 대사 실행 결과(S8 화면 표시용 — I-127). 영속하지 않는다(재기동 뒤 null, 여러 인스턴스면 인스턴스마다 다름). 권위 있는 신호는 `recon.run{result}`·
 * `recon.lag_seconds` 지표다.
 */
@Component
class ReconciliationRunMemory {
    private val last = AtomicReference<String?>(null)

    fun record(result: String) = last.set(result)

    fun lastResult(): String? = last.get()
}
