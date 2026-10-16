package com.paycore.master.merchant.application.required

import java.time.Duration

/*
 * 권리 Snapshot 발행·전달 Port(MASTER-SNAPSHOT-001 명세 §4.6–§4.8, §7).
 */

/**
 * 발행·lease·Relay 시간 설정(명세 §8). **운영 기본값을 코드에 두지 않는다** — 구현(adapter.integration 설정 바인딩)은 값이 없으면 기동을
 * 실패시킨다. 시험·로컬 Simulator는 Demo fixture 값을 명시적으로 넣는다(운영값 아님, 허용 §4.2 마지막 문단).
 */
interface EntitlementTiming {
    val leaseDuration: Duration
    val renewBefore: Duration
    val relayBatchSize: Int
    val claimLease: Duration
    val backoffInitial: Duration
    val backoffMax: Duration

    /** 0 이상 1 미만. backoff에 곱해 더하는 무작위 비율의 상한(0이면 결정적). */
    val backoffJitterRatio: Double
}
