package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.required.BusinessReadLimits
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * 사업자 조회 창(BUSINESS-PROFILE-001 §7.1 m-2 — `business-reads-per-minute`, Demo 30 — 운영값 아님): actor별 분 단위 고정 창 하나를 **목록·상세·이력·번호 조회가 공유**한다
 * ([BusinessQueryService]·[BusinessLookupService]가 같은 빈을 쓴다). 가맹점 조회 창(`MerchantLookupService` — `lookupPerMinute`)과 별개라 사업자 조회가 가맹점 화면을 429로 만들지 않는다.
 * 인스턴스 메모리(L-9 — LIVE Gate).
 */
@Component
class BusinessReadWindow internal constructor(limits: BusinessReadLimits, clock: () -> Long) {

    @Autowired
    constructor(limits: BusinessReadLimits) : this(limits, System::currentTimeMillis)

    private val windows = ActorMinuteWindows({ limits.businessReadsPerMinute }, clock)

    /** 1회 사용(확정 — 조회는 환불하지 않는다). 남은 횟수가 없으면 false. */
    fun admit(actorId: String): Boolean = windows.tryReserve(actorId) != null
}
