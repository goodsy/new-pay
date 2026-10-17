package com.paycore.simulator.authentication.application.required

import com.paycore.simulator.authentication.domain.LookupBehaviour
import com.paycore.simulator.authentication.domain.ResultRef
import java.time.Instant

/** 결과 조회 기록(append-only, 시험 관찰·"한 번만" 시나리오 판정용 — §13.4). */
interface LookupLogStore {
    fun countFor(resultRef: ResultRef): Long

    fun append(resultRef: ResultRef, at: Instant, behaviour: LookupBehaviour)
}
