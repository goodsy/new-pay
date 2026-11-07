package com.paycore.financial.acquiring.application.required

import java.time.Duration

/**
 * 재매입 실행 기한 정책 Port(ACQ-ADMIN-001 — 명세 I-2, D-OPS-002 선례). [maxExecutionWindow] = 승인 시각 → 실행 기한 최대 폭, [clockWindow] = 승인 시각이 FINANCIAL 시계보다
 * 앞설 수 있는 여유(서명 시각 창과 같은 값). 설정 값(Demo — 운영값 LIVE Gate).
 */
interface ReacquisitionExecutionPolicy {
    val maxExecutionWindow: Duration
    val clockWindow: Duration
}
