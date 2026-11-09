package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * 사업자등록번호 버킷(actor별 분당 — `registration-number-searches-per-minute`, Demo 10). **조회(MERCHANT-OPS-001 §5.2 등록번호 검색)와 등록(MERCHANT-OPS-002 C-13 사업자 등록)이
 * 같은 버킷을 쓴다**: 중복 거절 "이미 등록된 번호"는 검색 결과와 같은 정보(oracle)라 D-MOPS-006 ④로 같은 횟수 제한에 포함한다(L-7).
 * - 검색: [admit] = 예약 + 즉시 확정.
 * - 등록: MASTER 호출 전 [reserve](원자적 확인·차감) → 결과에 따라 확정(아무것도 안 함) 또는 [refund].
 * 인스턴스 메모리(L-9 — LIVE Gate).
 */
@Component
class RegistrationNumberBudget internal constructor(limits: MerchantOpsLimits, clock: () -> Long) {

    @Autowired
    constructor(limits: MerchantOpsLimits) : this(limits, System::currentTimeMillis)

    private val windows = ActorMinuteWindows({ limits.registrationNumberSearchesPerMinute }, clock)

    /** 확정된 1회 사용(검색). */
    fun admit(actorId: String): Boolean = windows.tryReserve(actorId) != null

    internal fun reserve(actorId: String): ActorMinuteWindows.Reservation? = windows.tryReserve(actorId)

    internal fun refund(reservation: ActorMinuteWindows.Reservation) = windows.refund(reservation)
}
