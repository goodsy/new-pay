package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.EnrollmentDraft
import com.paycore.admin.account.application.provided.SealedValue
import java.time.Instant
import java.util.Base64

/**
 * 등록 진행 상태의 세션 표현(S7 — D-40·D-74): **문자열 속성만**(바이트 값은 base64). 비밀번호 해시·TOTP 시크릿은 이미 `TotpSecretCipher`로 암호화된 대기 값뿐이고
 * 코드 원문은 없다(코드 hash만). 형식이 하나라도 틀리면 [read]가 null — 호출자는 진행 상태를 버린다.
 */
internal object EnrollmentSessionState {
    private const val P = "paycore.admin.enroll."
    const val PROGRESS = P + "progressId"
    const val CODE_HASH = P + "codeHash"
    const val TARGET = P + "target"
    const val PURPOSE = P + "purpose"
    const val BASIS = P + "basis"
    const val EXPIRES_AT = P + "expiresAt"
    const val PASSWORD_KV = P + "password.keyVersion"
    const val PASSWORD = P + "password"
    const val TOTP_KV = P + "totp.keyVersion"
    const val TOTP = P + "totp"
    val NAMES = listOf(PROGRESS, CODE_HASH, TARGET, PURPOSE, BASIS, EXPIRES_AT, PASSWORD_KV, PASSWORD, TOTP_KV, TOTP)

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun write(draft: EnrollmentDraft, set: (String, String?) -> Unit) {
        set(PROGRESS, draft.progressId)
        set(CODE_HASH, b64.encodeToString(draft.codeHash))
        set(TARGET, draft.targetActorId)
        set(PURPOSE, draft.purpose)
        set(BASIS, draft.basis)
        set(EXPIRES_AT, draft.expiresAt.toString())
        set(PASSWORD_KV, draft.pendingPasswordHash?.keyVersion?.toString())
        set(PASSWORD, draft.pendingPasswordHash?.let { b64.encodeToString(it.bytes) })
        set(TOTP_KV, draft.pendingTotpSecret?.keyVersion?.toString())
        set(TOTP, draft.pendingTotpSecret?.let { b64.encodeToString(it.bytes) })
    }

    fun read(get: (String) -> Any?): EnrollmentDraft? = runCatching {
        fun text(name: String): String? = get(name) as String?
        fun sealed(kv: String, value: String): SealedValue? {
            val version = text(kv)
            val bytes = text(value)
            require((version == null) == (bytes == null)) { "sealed value pair" }
            return if (version == null) null else SealedValue(version.toInt(), unb64.decode(bytes))
        }
        EnrollmentDraft(
            progressId = text(PROGRESS) ?: return null,
            codeHash = unb64.decode(requireNotNull(text(CODE_HASH))),
            targetActorId = requireNotNull(text(TARGET)),
            purpose = requireNotNull(text(PURPOSE)),
            basis = requireNotNull(text(BASIS)),
            expiresAt = Instant.parse(requireNotNull(text(EXPIRES_AT))),
            pendingPasswordHash = sealed(PASSWORD_KV, PASSWORD),
            pendingTotpSecret = sealed(TOTP_KV, TOTP),
        )
    }.getOrNull()
}
