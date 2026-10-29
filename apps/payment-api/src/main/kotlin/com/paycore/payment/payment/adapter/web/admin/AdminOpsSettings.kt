package com.paycore.payment.payment.adapter.web.admin

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ADMIN 채널 bulkhead 설정(ADMIN-PAYMENT-OPS-001 §4.7 — **운영 기본값 없음**, 값이 없으면 기동 실패. Demo 값은 시험·로컬 값이고 운영값은 LIVE Gate, I-24). 같은 prefix의 기한·안정 지연
 * (`max-execution-window`·`decision-stable-delay`)은 그 값을 쓰는 persistence 구성 `OperatorDecisionPolicyConfiguration` 한 곳에서만 바인딩·검증한다(review n-4 — 출처 하나).
 *
 * @param readPermits 관리 조회 동시 상한(대상·결정·목록 공용, Demo 4 — 대기 0, 초과 503 `ADMIN_READ_BUSY`).
 * @param commandPermits 실행·기한 확인 동시 상한(공용, Demo 2 — 대기 0, 초과 503 `ADMIN_COMMAND_BUSY`).
 * @param publicPoolReserve 공개 경로에 남길 DB 연결 수(Demo 4) — `readPermits + commandPermits ≤ 풀 크기 − reserve` 아니면 기동 거부(persistence `AdminChannelPoolBudget`).
 */
@ConfigurationProperties(prefix = "paycore.payment.admin-ops")
class AdminOpsSettings(val readPermits: Int, val commandPermits: Int, val publicPoolReserve: Int) {
    init {
        require(readPermits in 1..MAX_PERMITS && commandPermits in 1..MAX_PERMITS) { "admin-ops permits must be within 1..$MAX_PERMITS" }
        require(publicPoolReserve >= 1) { "admin-ops public-pool-reserve must be positive" }
    }

    override fun toString(): String = "AdminOpsSettings(readPermits=$readPermits, commandPermits=$commandPermits, publicPoolReserve=$publicPoolReserve)"

    private companion object {
        const val MAX_PERMITS = 64
    }
}
