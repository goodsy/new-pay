package com.paycore.payment.payment.adapter.persistence

/**
 * 풀 대비 permit 기동 검사(ADMIN-PAYMENT-OPS-001 §4.7 M-7, I-09): 관리 조회·명령 permit이 모두 DB 연결을 잡아도 공개 경로에 `publicPoolReserve`개가 남아야 한다 —
 * `readPermits + commandPermits ≤ poolSize − reserve`(명령 permit은 실행·기한 확인 공용, rev 0.4 L-3). 풀 크기는 실행 중 Hikari 값(설정 미지정 기본 10). DB 풀을 아는 persistence 구성에
 * 둔다(web adapter는 DataSource에 의존하지 않는다 — ArchitectureSpec, I-50).
 */
class AdminChannelPoolBudget(readPermits: Int, commandPermits: Int, publicPoolReserve: Int, poolSize: Int) {
    init {
        check(readPermits + commandPermits <= poolSize - publicPoolReserve) {
            "admin-ops read-permits + command-permits must leave public-pool-reserve connections of the pool"
        }
    }
}
