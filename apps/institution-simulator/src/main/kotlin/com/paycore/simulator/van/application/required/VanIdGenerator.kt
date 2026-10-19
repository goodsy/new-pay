package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanDecisionIds
import java.time.Instant

/** 한 번의 판정에 쓸 기관 식별자 묶음(결정·예약·종결 ID는 시각 [at]의 ULID, 승인번호·기관 거래 식별자는 Demo 형식 난수). */
fun interface VanIdGenerator {
    fun next(at: Instant): VanDecisionIds
}
