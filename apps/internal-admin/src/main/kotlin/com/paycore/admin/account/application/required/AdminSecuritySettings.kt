package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.LoginAttemptPolicy
import java.time.Duration

/**
 * 계정 보안 수치(D-SV — **Demo 값, 운영값 아님**, Q-ADMIN-SEC-VALUES·LIVE Gate). 설정에서 온다(기본값 없음 — 없으면 기동 실패).
 */
interface AdminSecuritySettings {
    val loginAttemptPolicy: LoginAttemptPolicy
    val totpWindowSteps: Int
    val enrollmentCodeValidity: Duration
    val enrollmentStepValidity: Duration
    val passwordMinLength: Int
    val passwordMaxBytes: Int
    val totpIssuer: String
    val anonymousSessionIdle: Duration
    val passwordStepIdle: Duration
    val authenticatedSessionIdle: Duration
    val absoluteSessionLimit: Duration
}
