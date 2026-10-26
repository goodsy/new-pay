package com.paycore.admin.account.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * admin_enrollment_code 행. updatable 열은 `used_at`·`revoked_at`뿐(DB 열 단위 UPDATE 권한과 같음 — R-12). 소비·폐기는 dirty checking이 아니라
 * 조건부 JPQL UPDATE(원자 소비)로 한다 — [JpaEnrollmentCodeStore]. PK는 SHA-256 32바이트(배열 내용으로 같음을 판단하는 [JpaEnrollmentCodeKey]).
 */
@Entity
@Table(name = "admin_enrollment_code")
class JpaEnrollmentCodeEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: JpaEnrollmentCodeKey
        protected set

    @field:Column(name = "target_actor_id", nullable = false, updatable = false, length = 30)
    lateinit var targetActorId: String
        protected set

    @field:Column(name = "purpose", nullable = false, updatable = false, length = 24)
    lateinit var purpose: String
        protected set

    @field:Column(name = "basis", nullable = false, updatable = false, length = 40)
    lateinit var basis: String
        protected set

    @field:Column(name = "recovery_episode_no", updatable = false)
    var recoveryEpisodeNo: Long? = null
        protected set

    @field:Column(name = "recovery_roles", updatable = false)
    var recoveryRoles: String? = null
        protected set

    @field:Column(name = "issued_at", nullable = false, updatable = false)
    lateinit var issuedAt: Instant
        protected set

    @field:Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant
        protected set

    @field:Column(name = "used_at")
    var usedAt: Instant? = null
        protected set

    @field:Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is JpaEnrollmentCodeEntity && key == other.key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaEnrollmentCodeEntity(purpose=$purpose)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(hash: ByteArray, target: String, purpose: String, basis: String, episode: Long?, roles: String?, issuedAt: Instant, expiresAt: Instant) =
            JpaEnrollmentCodeEntity().also {
                it.key = JpaEnrollmentCodeKey(hash)
                it.targetActorId = target
                it.purpose = purpose
                it.basis = basis
                it.recoveryEpisodeNo = episode
                it.recoveryRoles = roles
                it.issuedAt = issuedAt
                it.expiresAt = expiresAt
            }
    }
}

/** 코드 검증값(SHA-256) PK. equals/hashCode는 배열 내용 기준. */
@Embeddable
class JpaEnrollmentCodeKey() : Serializable {
    @field:Column(name = "code_hash", nullable = false, updatable = false, length = 32, columnDefinition = "binary(32)")
    lateinit var codeHash: ByteArray
        protected set

    constructor(hash: ByteArray) : this() {
        codeHash = hash.copyOf()
    }

    override fun equals(other: Any?): Boolean = other is JpaEnrollmentCodeKey && codeHash.contentEquals(other.codeHash)

    override fun hashCode(): Int = codeHash.contentHashCode()

    override fun toString(): String = "JpaEnrollmentCodeKey(***)"

    private companion object {
        private const val serialVersionUID = 1L
    }
}
