package com.paycore.admin.account.application.provided

import java.time.Instant

/**
 * 세션에 저장하는 로그인 상태(**문자열 값만** — §17.5: Java 직렬화 경로 없음). 웹 adapter가 쓰고 보안 구성이 읽는다. 형식이 맞지 않으면 [read]가 null(세션 폐기 대상).
 * 역할·권한은 저장하지 않는다(요청마다 계정 현재값).
 */
data class AdminSessionState(
    val stage: Stage,
    val actorId: String,
    val securityVersion: Long,
    val sessionGeneration: Long? = null,
    val authenticatedAt: Instant? = null,
    val stepExpiresAt: Instant? = null,
) {
    enum class Stage { PASSWORD_VERIFIED, AUTHENTICATED }

    init {
        require(ACTOR.matches(actorId)) { "actor id format" }
        require(stage != Stage.AUTHENTICATED || (sessionGeneration != null && authenticatedAt != null)) { "an authenticated session records generation and time" }
        require(stage != Stage.PASSWORD_VERIFIED || stepExpiresAt != null) { "a password-verified session records its step expiry" }
    }

    fun toAttributes(): Map<String, String?> = mapOf(
        STAGE to stage.name, ACTOR_ID to actorId, SECURITY_VERSION to securityVersion.toString(), SESSION_GENERATION to sessionGeneration?.toString(),
        AUTHENTICATED_AT to authenticatedAt?.toString(), STEP_EXPIRES_AT to stepExpiresAt?.toString(),
    )

    companion object {
        const val STAGE = "paycore.admin.stage"
        const val ACTOR_ID = "paycore.admin.actorId"
        const val SECURITY_VERSION = "paycore.admin.securityVersion"
        const val SESSION_GENERATION = "paycore.admin.sessionGeneration"
        const val AUTHENTICATED_AT = "paycore.admin.authenticatedAt"
        const val STEP_EXPIRES_AT = "paycore.admin.stepExpiresAt"
        val NAMES = listOf(STAGE, ACTOR_ID, SECURITY_VERSION, SESSION_GENERATION, AUTHENTICATED_AT, STEP_EXPIRES_AT)
        private val ACTOR = Regex("^adm_[0-9A-HJKMNP-TV-Z]{26}$")

        /** 세션 속성에서 읽는다. 단계 속성이 없으면 null(로그인 전), 형식 오류도 null — 호출자는 [isCorrupt]로 구분한다. */
        fun read(attribute: (String) -> Any?): AdminSessionState? = try {
            val stage = (attribute(STAGE) as? String)?.let { Stage.valueOf(it) }
            stage?.let {
                AdminSessionState(
                    it, attribute(ACTOR_ID) as String, (attribute(SECURITY_VERSION) as String).toLong(),
                    (attribute(SESSION_GENERATION) as? String)?.toLong(), (attribute(AUTHENTICATED_AT) as? String)?.let(Instant::parse),
                    (attribute(STEP_EXPIRES_AT) as? String)?.let(Instant::parse),
                )
            }
        } catch (_: RuntimeException) {
            null
        }

        /** 단계 속성은 있는데 읽을 수 없으면 손상(변조·형식 변경) — 세션을 폐기한다. */
        fun isCorrupt(attribute: (String) -> Any?): Boolean = attribute(STAGE) != null && read(attribute) == null
    }
}
