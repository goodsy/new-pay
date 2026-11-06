package com.paycore.financial.acquiring.application.required

import java.time.Duration

/**
 * 결과 마감 운영값 Port(설정 — **Demo 값, 운영값은 LIVE Gate §14-4**). [resultDeadline] = 전송 완료(TRANSMITTED) 뒤 이 시간이 지나도록 결과가 없는 레코드를 UNKNOWN으로
 * 기록한다(D-ACQ-004). null = 설정 없음 → 결과 마감을 하지 않는다(fail-closed — 임의 기본값으로 UNKNOWN을 만들지 않는다).
 */
interface AcquisitionResultPolicy {
    val resultDeadline: Duration?
}
