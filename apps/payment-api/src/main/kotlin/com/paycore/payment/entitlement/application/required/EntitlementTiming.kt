package com.paycore.payment.entitlement.application.required

import java.time.Duration

/** 판정 설정(명세 §8). 운영 기본값 없음 — 구현은 값이 없으면 기동을 실패시킨다. */
interface EntitlementTiming {
    /** MASTER·PAYMENT 시계 오차 여유. `t ≥ leaseUntil − margin`이면 만료(Demo 2초, 시험 100ms — 운영 미결). */
    val clockSkewMargin: Duration
}
