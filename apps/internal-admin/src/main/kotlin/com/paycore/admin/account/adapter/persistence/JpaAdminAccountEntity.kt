package com.paycore.admin.account.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * admin_account 행(행 모양만 — 업무 규칙은 domain [com.paycore.admin.account.domain.AdminAccount]). 생성 불변 열(actor_id·login_name·created_at)은
 * `updatable = false` — Hibernate는 updatable 열 전체를 SET에 넣으므로 이 목록이 DB 열 단위 UPDATE 권한과 같아야 한다(R-12, AdminPersistenceMappingSpec).
 * 역할 집합은 MySQL SET의 쉼표 문자열(정의 순서로 정규화되어 돌아온다). data class 아님, equals/hashCode = actor ID, toString에 해시·암호문 없음.
 */
@Entity
@Table(name = "admin_account")
class JpaAdminAccountEntity protected constructor() {
    @field:Id
    @field:Column(name = "actor_id", nullable = false, updatable = false, length = 30)
    lateinit var actorId: String
        protected set

    @field:Column(name = "login_name", nullable = false, updatable = false, length = 64)
    lateinit var loginName: String
        protected set

    @field:Column(name = "display_name", nullable = false, length = 100)
    lateinit var displayName: String

    @field:Column(name = "status", nullable = false, length = 24)
    lateinit var status: String

    @field:Column(name = "status_before_suspend", length = 24)
    var statusBeforeSuspend: String? = null

    @field:Column(name = "pending_purpose", length = 24)
    var pendingPurpose: String? = null

    @field:Column(name = "pending_basis", length = 40)
    var pendingBasis: String? = null

    @field:Column(name = "credential_basis", nullable = false, length = 40)
    lateinit var credentialBasis: String

    @field:Column(name = "credential_basis_requester", length = 30)
    var credentialBasisRequester: String? = null

    @field:Column(name = "credential_basis_approver", length = 30)
    var credentialBasisApprover: String? = null

    /** D-98: 등록 완료 전까지 함께 배제되는 이전 발급자(쉼표 구분 actor ID, 없으면 null). */
    @field:Column(name = "credential_basis_carried", length = 1000)
    var credentialBasisCarried: String? = null

    @field:Column(name = "password_hash", length = 100)
    var passwordHash: String? = null

    @field:Column(name = "totp_secret_ciphertext", length = 64)
    var totpSecretCiphertext: ByteArray? = null

    @field:Column(name = "totp_key_version")
    var totpKeyVersion: Int? = null

    @field:Column(name = "last_totp_step")
    var lastTotpStep: Long? = null

    @field:Column(name = "failed_attempts", nullable = false)
    var failedAttempts: Int = 0

    @field:Column(name = "locked_at")
    var lockedAt: Instant? = null

    @field:Column(name = "active_roles", nullable = false)
    var activeRoles: String = ""

    @field:Column(name = "suspended_roles", nullable = false)
    var suspendedRoles: String = ""

    @field:Column(name = "session_generation", nullable = false)
    var sessionGeneration: Long = 0

    @field:Column(name = "security_version", nullable = false)
    var securityVersion: Long = 0

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && actorId == (other as JpaAdminAccountEntity).actorId)

    override fun hashCode(): Int = actorId.hashCode()

    override fun toString(): String = "JpaAdminAccountEntity(actorId=$actorId, status=$status)"

    companion object {
        fun newRow(actorId: String, loginName: String, createdAt: Instant) = JpaAdminAccountEntity().also {
            it.actorId = actorId
            it.loginName = loginName
            it.createdAt = createdAt
        }
    }
}
