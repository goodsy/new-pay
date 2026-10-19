package com.paycore.simulator.van.application.required

import com.paycore.simulator.van.domain.VanNamespace
import java.time.Instant

/** 가상 VAN 조회 기록 Port(append-only, AUTH-001 §5.5 `van_inquiry_log`). "첫 조회만 UNAVAILABLE" 시나리오는 이 기록 수로 판정한다. TX 없이 호출하면 거부한다. */
interface VanInquiryLogStore {
    fun count(namespace: VanNamespace): Long

    fun append(namespace: VanNamespace, inquiryId: String, closeIfAbsent: Boolean, result: String, at: Instant)
}
