package com.paycore.admin.account.application.provided

/**
 * 인증 실패 기록(비밀번호·로그인 TOTP·재인증 TOTP 합산 — D-ADMIN1-005, §4.3.5·§4.7.4 m-3). **독립 로컬 TX**: 계정 행 잠금 → 실패 계수 +1(ACTIVE만) →
 * 한도면 LOCKED·세대 +1(현재 세션도 끊김) → 감사 → 커밋. 승인 TX가 rollback된 뒤 호출해 계수가 남게 한다. 동시 실패는 행 잠금으로 직렬화되어 하나도 잃지 않는다.
 */
interface AuthenticationFailureRecorder {
    fun recordFailure(actorId: String, step: AuthenticationStep, context: AdminRequestContext): AuthenticationFailureOutcome
}

enum class AuthenticationStep { PASSWORD, LOGIN_TOTP, STEP_UP_TOTP }

enum class AuthenticationFailureOutcome { COUNTED, LOCKED, NOT_COUNTED, UNKNOWN_ACCOUNT }
