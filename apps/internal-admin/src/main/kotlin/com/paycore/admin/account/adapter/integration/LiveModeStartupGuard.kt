package com.paycore.admin.account.adapter.integration

import java.security.MessageDigest
import java.time.Duration

/**
 * LIVE 기동 가드(§4.8.2 n-4 + S3 리뷰 m-5). LIVE에서 다음 중 하나라도 있으면 기동을 거부한다(보고에는 **설정 이름만** — 키 값·지문 없음):
 * - TOTP 암호 키·login_name HMAC 키가 저장소의 알려진 Demo 키(시험 설정 키의 SHA-256 지문 목록)와 같음(키 없음은 설정 바인딩 단계에서 이미 실패).
 * - 보안 수치가 Demo 값(D-SV — 운영값은 Q-ADMIN-SEC-VALUES 답변 전, LIVE Gate 차단). BCrypt cost가 LIVE 하한 미만.
 * LIVE 하한 12는 **잠정**(Demo 값과 같은 수 — 운영 하한은 Q-ADMIN-SEC-VALUES로 확정). Demo 값 12와 하한 12가 겹쳐 LIVE에서 실제로 허용되는 최소 cost는 13이다.
 * DEMO는 검사하지 않는다 — `deployment-mode=DEMO`로 운영에 띄우면 가드가 우회된다(구조적 한계, LIVE Gate 확인 항목 — 명세 D-79).
 */
object LiveModeStartupGuard {
    const val LIVE_MIN_BCRYPT_COST = 12

    /** 저장소에 있는 Demo 키(src/test/resources/config/application.yml)의 SHA-256 지문. 키를 바꾸면 여기도 함께 바꾼다. */
    private val KNOWN_DEMO_KEY_FINGERPRINTS = setOf(
        "a9e7a6c4afeb073960a13a57ab73bfd92732e0fb1c7af99daaa6c183f82da16b",
        "5aa9dcd08d7802c1b693318baf244496d9bf6a59bd23e12af4252bd9cc8ae2aa",
    )

    private const val P = "paycore.admin.security."

    fun violations(mode: DeploymentMode, security: AdminSecurityProperties, springSessionTimeout: Duration?, recoveryEpisodeValidity: Duration?): List<String> {
        if (mode != DeploymentMode.LIVE) return emptyList()
        val found = mutableListOf<String>()
        security.totpCipher.keys.toSortedMap().forEach { (version, key) -> if (isDemoKey(key)) found += "${P}totp-cipher.keys.$version is a known demo key" }
        if (isDemoKey(security.loginNameHmacKey)) found += "${P}login-name-hmac-key is a known demo key"
        if (security.bcryptCost < LIVE_MIN_BCRYPT_COST) found += "${P}bcrypt-cost is below the LIVE minimum $LIVE_MIN_BCRYPT_COST"
        val demoValues: List<Pair<String, Boolean>> = listOf(
            "login-max-failures" to (security.loginAttemptPolicy.maxFailures == 5),
            "totp-window-steps" to (security.totpWindowSteps == 1),
            "enrollment-code-validity" to (security.enrollmentCodeValidity == Duration.ofHours(24)),
            "enrollment-step-validity" to (security.enrollmentStepValidity == Duration.ofMinutes(10)),
            "password-min-length" to (security.passwordMinLength == 12),
            "bcrypt-cost" to (security.bcryptCost == 12),
            "totp-issuer" to security.totpIssuer.contains("Demo", ignoreCase = true),
            "session.anonymous-idle" to (security.session.anonymousIdle == Duration.ofMinutes(10)),
            "session.password-step-idle" to (security.session.passwordStepIdle == Duration.ofMinutes(5)),
            "session.authenticated-idle" to (security.session.authenticatedIdle == Duration.ofMinutes(15)),
            "session.absolute" to (security.session.absolute == Duration.ofHours(8)),
        )
        demoValues.filter { it.second }.forEach { found += "$P${it.first} is the demo value" }
        if (springSessionTimeout == null || springSessionTimeout == Duration.ofMinutes(10)) found += "spring.session.timeout is the demo value"
        // S6 리뷰 Q-S6-2(Coordinator 판정): 복구 회차 유효 Demo 값(24h — D-SV)도 LIVE에서 거부.
        if (recoveryEpisodeValidity == null || recoveryEpisodeValidity == Duration.ofHours(24)) found += "paycore.admin.recovery.episode-validity is the demo value"
        return found
    }

    /** 위반이 있으면 기동 실패(설정 이름 목록만 담는다). */
    fun check(mode: DeploymentMode, security: AdminSecurityProperties, springSessionTimeout: Duration?, recoveryEpisodeValidity: Duration?) {
        val found = violations(mode, security, springSessionTimeout, recoveryEpisodeValidity)
        check(found.isEmpty()) { "LIVE deployment refused: " + found.joinToString("; ") }
    }

    /**
     * TOTP 암호 키 전부와 login_name HMAC 키가 저장소의 알려진 Demo 키인가(ADMIN-DEMO-LOGIN-001 리뷰 MINOR-2(b) — **보고용**: Demo 배치는 gen-env로 만든 임의 키를 쓰므로
     * "알려진 Demo 키가 아님"이 곧 운영 키라는 근거가 없다. 그래서 거부하지 않고 기동 WARN에 분류만 남긴다).
     */
    fun usesKnownDemoKeys(security: AdminSecurityProperties): Boolean =
        security.totpCipher.keys.values.all(::isDemoKey) && isDemoKey(security.loginNameHmacKey)

    private fun isDemoKey(key: ByteArray): Boolean =
        MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) } in KNOWN_DEMO_KEY_FINGERPRINTS
}
