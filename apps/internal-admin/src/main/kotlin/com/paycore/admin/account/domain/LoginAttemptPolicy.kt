package com.paycore.admin.account.domain

/**
 * 인증 실패 잠금 정책(D-ADMIN1-005): 비밀번호·로그인 TOTP·재인증 TOTP 실패를 합산하고 [maxFailures]번째 실패에서 잠근다. 자동 해제 없음.
 * 값은 설정(Demo 5 — 운영값 아님, Q-ADMIN-SEC-VALUES·LIVE Gate).
 */
data class LoginAttemptPolicy(val maxFailures: Int) {
    init {
        require(maxFailures in 1..100) { "max failures must be 1-100" }
    }
}
