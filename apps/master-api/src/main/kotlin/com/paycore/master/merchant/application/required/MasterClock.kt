package com.paycore.master.merchant.application.required

import java.time.Instant

/**
 * 서버 시각 Port(명세 §6, review m-3). 관리 명령의 시각(기록·효력 판정·소급 금지 기준)은 호출자가 아니라 이 Port에서 온다 — 호출자가 과거·미래
 * 시각을 넣어 적용 Revision(D-MD-011) 판정이나 소급 금지(D-MD-005)를 우회하지 못하게. 시험은 대역으로 시각을 고정한다.
 */
interface MasterClock {
    fun now(): Instant
}
