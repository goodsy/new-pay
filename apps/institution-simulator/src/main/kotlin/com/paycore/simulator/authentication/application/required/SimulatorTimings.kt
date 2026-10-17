package com.paycore.simulator.authentication.application.required

import java.time.Duration

/** Simulator 시간·URL 설정(Demo — 운영값 아님, 운영 기본값 없음). */
interface SimulatorTimings {
    val transactionTtl: Duration
    val resultValidity: Duration

    /** 고객 화면 URL의 origin(`https://…`, path 없음). */
    val customerBaseUrl: String

    /** "첫 조회 지연" 시나리오의 대기(PG read timeout보다 길게 — Demo). */
    val lookupDelay: Duration
}
