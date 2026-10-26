package com.paycore.admin.account.application.provided

import java.time.Duration
import java.time.Instant

/**
 * 2단계 로그인(§4.3.2 — D-ADMIN1-001·005). 응답은 원인 비구분 하나(`INVALID_CREDENTIALS`).
 * - [verifyPassword]: (a) 비잠금 읽기(jOOQ — TX 밖)로 해시·`security_version`(없는 계정이면 같은 cost 더미 해시) → BCrypt 비교 **정확히 1회**(잠금 밖) →
 *   (b) 로컬 TX 1회: 계정 행 `FOR UPDATE` → 상태(LOCKED·SUSPENDED·PENDING)·`security_version` 변화를 **먼저** 판정 → BCrypt 결과 적용(실패면 계수+1, 한도면 LOCKED)
 *   → 감사 1행(없는 계정도 TX 1회·감사 1행, login_name은 비밀 키 HMAC). (a)와 (b) 사이 `security_version`이 바뀌었으면 결과를 적용하지 않고 일반 실패(계수 없음).
 * - [verifyTotp]: 로컬 TX 1회: 계정 `FOR UPDATE` → ACTIVE·`security_version`이 비밀번호 단계 값과 같은지 → TOTP(±창, 마지막 단계 초과) → 성공이면 단계 기록·계수 0·
 *   **세대 +1(새 로그인 우선)**·감사 / 실패면 계수+1(합산), 한도면 LOCKED.
 */
interface AdminLoginUseCase {
    fun verifyPassword(loginName: String, password: CharArray, context: AdminRequestContext): PasswordStepResult

    fun verifyTotp(verified: PasswordVerified, code: String, context: AdminRequestContext): TotpStepResult

    fun logout(actorId: String, context: AdminRequestContext)
}

/** 비밀번호 단계를 통과한 세션의 값(세션 속성에 문자열로 저장). [stepExpiresAt]까지만 TOTP 단계를 받는다. */
data class PasswordVerified(val actorId: String, val securityVersion: Long, val stepExpiresAt: Instant)

sealed interface PasswordStepResult {
    data class Verified(val verified: PasswordVerified, val stepIdle: Duration) : PasswordStepResult

    data object InvalidCredentials : PasswordStepResult
}

sealed interface TotpStepResult {
    /** 로그인 완료. 세션에는 [sessionGeneration]·[securityVersion]·[authenticatedAt]을 기록하고 idle·절대 한도를 건다. */
    data class Authenticated(
        val actorId: String,
        val sessionGeneration: Long,
        val securityVersion: Long,
        val authenticatedAt: Instant,
        val idle: Duration,
    ) : TotpStepResult

    /** 실패. [endSession]이면(상태·`security_version` 변화·잠금) 진행 세션을 폐기한다. */
    data class Rejected(val endSession: Boolean) : TotpStepResult
}
