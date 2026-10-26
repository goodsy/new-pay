package com.paycore.admin.account.domain

import java.security.MessageDigest
import java.time.Instant

/**
 * 1회용 등록 코드 행(§4.3.6 m-5). 원문은 저장하지 않고 정규화 원문의 SHA-256([EnrollmentCodeHash])만 PK로 둔다.
 * 사용 가능 = 미사용·미폐기·만료 전(서버 시계로 TX 안에서 판정). 복구 코드(RECOVERY)는 회차와 복구 시점 SA·APPROVER 보유 집합을 가진다(§4.9.2).
 */
data class EnrollmentCode(
    val hash: EnrollmentCodeHash,
    val target: AdminActorId,
    val purpose: EnrollmentPurpose,
    val basis: String,
    val recoveryEpisode: Long?,
    val recoveryRoles: Set<AdminRole>?,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val revokedAt: Instant?,
) {
    init {
        PendingEnrollment(purpose, basis)
        require(expiresAt.isAfter(issuedAt)) { "an enrollment code expires after it is issued" }
        require(purpose != EnrollmentPurpose.RECOVERY || (recoveryEpisode != null && recoveryRoles != null)) { "a RECOVERY code records its episode and roles" }
        require(recoveryRoles == null || purpose == EnrollmentPurpose.RECOVERY) { "recorded roles exist only on RECOVERY codes" }
        require(recoveryRoles == null || recoveryRoles.all { it == AdminRole.SECURITY_ADMIN || it == AdminRole.APPROVER }) { "recovery records only SECURITY_ADMIN and APPROVER" }
    }

    val pending: PendingEnrollment get() = PendingEnrollment(purpose, basis)

    fun isUsable(now: Instant): Boolean = usedAt == null && revokedAt == null && expiresAt.isAfter(now)

    companion object {
        fun issue(hash: EnrollmentCodeHash, target: AdminActorId, pending: PendingEnrollment, issuedAt: Instant, expiresAt: Instant): EnrollmentCode =
            EnrollmentCode(hash, target, pending.purpose, pending.basis, null, null, issuedAt, expiresAt, null, null)
    }
}

/** 등록 코드 검증값: 정규화 원문(대문자 base32 32자)의 SHA-256 32바이트. toString은 앞 8자리 hex만(원문 아님 — 그래도 전체를 남기지 않는다). */
class EnrollmentCodeHash(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(value.size == SHA256_LENGTH) { "enrollment code hash is 32 bytes" }
    }

    val bytes: ByteArray get() = value.copyOf()

    override fun equals(other: Any?): Boolean = other is EnrollmentCodeHash && MessageDigest.isEqual(other.value, value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "EnrollmentCodeHash(" + value.take(4).joinToString("") { "%02x".format(it) } + "…)"

    companion object {
        private const val SHA256_LENGTH = 32
        private val NORMALIZED = Regex("^[A-Z2-7]{32}$")

        /** 사용자 입력(공백·하이픈 허용, 대소문자 무관)을 대문자 base32 32자로 정규화한다. 형식이 아니면 null. */
        fun normalize(raw: String): String? = raw.filterNot { it == ' ' || it == '-' }.uppercase().takeIf { NORMALIZED.matches(it) }

        fun of(normalizedCode: String): EnrollmentCodeHash {
            require(NORMALIZED.matches(normalizedCode)) { "enrollment code must be normalized" }
            return EnrollmentCodeHash(MessageDigest.getInstance("SHA-256").digest(normalizedCode.toByteArray(Charsets.US_ASCII)))
        }
    }
}
