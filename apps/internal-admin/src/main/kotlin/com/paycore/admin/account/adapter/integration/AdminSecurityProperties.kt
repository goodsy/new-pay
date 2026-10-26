package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.domain.LoginAttemptPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.Base64

/**
 * 계정 보안 설정(`paycore.admin.security` — D-SV **Demo 값, 운영값 아님**, Q-ADMIN-SEC-VALUES·LIVE Gate). 기본값 없음: 값이 없거나 틀리면 기동 실패.
 * 키(TOTP 암호 키 버전별 256비트·login_name HMAC 키)는 base64 원문을 환경변수로만 받는다(저장소에 운영 키 없음 — 시험 키는 시험 설정에만).
 * LIVE 배치 모드의 Demo 키·Demo 수치·BCrypt 하한 가드는 [LiveModeStartupGuard](S4). toString은 키를 내지 않는다.
 */
@ConfigurationProperties(prefix = "paycore.admin.security")
class AdminSecurityProperties(
    loginMaxFailures: Int,
    override val totpWindowSteps: Int,
    override val enrollmentCodeValidity: Duration,
    override val enrollmentStepValidity: Duration,
    override val passwordMinLength: Int,
    override val passwordMaxBytes: Int,
    val bcryptCost: Int,
    override val totpIssuer: String,
    val totpCipher: TotpCipher,
    loginNameHmacKey: String,
    val session: Session,
) : AdminSecuritySettings {

    override val loginAttemptPolicy = LoginAttemptPolicy(loginMaxFailures)
    override val anonymousSessionIdle: Duration get() = session.anonymousIdle
    override val passwordStepIdle: Duration get() = session.passwordStepIdle
    override val authenticatedSessionIdle: Duration get() = session.authenticatedIdle
    override val absoluteSessionLimit: Duration get() = session.absolute

    internal val loginNameHmacKey: ByteArray = decodeKey(loginNameHmacKey, "login-name-hmac-key")

    init {
        require(totpWindowSteps in 0..MAX_TOTP_WINDOW) { "totp-window-steps must be 0-$MAX_TOTP_WINDOW" }
        require(!enrollmentCodeValidity.isNegative && !enrollmentCodeValidity.isZero) { "enrollment-code-validity must be positive" }
        require(!enrollmentStepValidity.isNegative && !enrollmentStepValidity.isZero) { "enrollment-step-validity must be positive" }
        require(passwordMinLength >= 1 && passwordMaxBytes in passwordMinLength..BCRYPT_MAX_BYTES) { "password length bounds must be 1 <= min <= max <= 72 bytes" }
        require(bcryptCost in BCRYPT_MIN_COST..BCRYPT_MAX_COST) { "bcrypt-cost must be $BCRYPT_MIN_COST-$BCRYPT_MAX_COST" }
        require(totpIssuer.isNotBlank() && totpIssuer.length <= ISSUER_MAX && !totpIssuer.contains(':')) { "totp-issuer must be 1-$ISSUER_MAX characters without ':'" }
    }

    /** 세션 한도(D-SV Demo): 익명·등록 진행 idle, 비밀번호 단계 idle, 로그인 idle, 로그인 절대 한도. */
    class Session(val anonymousIdle: Duration, val passwordStepIdle: Duration, val authenticatedIdle: Duration, val absolute: Duration) {
        init {
            listOf(anonymousIdle, passwordStepIdle, authenticatedIdle, absolute).forEach { require(!it.isNegative && !it.isZero) { "session limits must be positive" } }
            require(authenticatedIdle <= absolute) { "the idle limit cannot exceed the absolute limit" }
        }
    }

    class TotpCipher(val currentKeyVersion: Int, keys: Map<String, String>) {
        internal val keys: Map<Int, ByteArray> = keys.entries.associate { (version, key) ->
            (version.toIntOrNull() ?: throw IllegalArgumentException("totp-cipher key versions are integers")) to decodeKey(key, "totp-cipher key")
        }

        override fun toString(): String = "TotpCipher(currentKeyVersion=$currentKeyVersion, keyVersions=${keys.keys.sorted()})"
    }

    override fun toString(): String =
        "AdminSecurityProperties(loginMaxFailures=${loginAttemptPolicy.maxFailures}, totpWindowSteps=$totpWindowSteps, bcryptCost=$bcryptCost, totpCipher=$totpCipher)"

    private companion object {
        const val MAX_TOTP_WINDOW = 3
        const val BCRYPT_MAX_BYTES = 72
        const val BCRYPT_MIN_COST = 4
        const val BCRYPT_MAX_COST = 31
        const val ISSUER_MAX = 40
        const val KEY_BYTES = 32

        fun decodeKey(base64: String, name: String): ByteArray {
            val key = runCatching { Base64.getDecoder().decode(base64) }.getOrElse { throw IllegalArgumentException("$name must be base64") }
            require(key.size == KEY_BYTES) { "$name must be 256-bit" }
            return key
        }
    }
}
