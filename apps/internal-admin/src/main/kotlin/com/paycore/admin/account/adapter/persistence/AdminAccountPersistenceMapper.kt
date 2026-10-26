package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.CredentialBasis
import com.paycore.admin.account.domain.DisplayName
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.LoginName
import com.paycore.admin.account.domain.PasswordHash
import com.paycore.admin.account.domain.PendingEnrollment
import com.paycore.admin.account.domain.TotpCredential

/**
 * admin_account 행 ↔ domain. 복원은 [AdminAccount.reconstitute]가 불가능한 조합(ACTIVE인데 자격 없음 등)을 거부한다.
 * [copyMutable]은 updatable 열만 옮긴다(생성 불변 열은 행 생성 때 한 번만 — Entity `updatable = false`와 같은 목록).
 */
internal object AdminAccountPersistenceMapper {

    fun toDomain(row: JpaAdminAccountEntity): AdminAccount = AdminAccount.reconstitute(
        id = AdminActorId(row.actorId),
        loginName = LoginName(row.loginName),
        displayName = DisplayName(row.displayName),
        status = AccountStatus.valueOf(row.status),
        statusBeforeSuspend = row.statusBeforeSuspend?.let(AccountStatus::valueOf),
        pending = row.pendingPurpose?.let { PendingEnrollment(EnrollmentPurpose.valueOf(it), requireNotNull(row.pendingBasis) { "pending basis is missing" }) },
        credentialBasis = CredentialBasis(
            row.credentialBasis, row.credentialBasisRequester?.let(::AdminActorId), row.credentialBasisApprover?.let(::AdminActorId),
            row.credentialBasisCarried?.split(',')?.filter { it.isNotEmpty() }?.map(::AdminActorId)?.toSet().orEmpty(),
        ),
        passwordHash = row.passwordHash?.let(::PasswordHash),
        totp = row.totpSecretCiphertext?.let { TotpCredential(it, requireNotNull(row.totpKeyVersion) { "TOTP key version is missing" }) },
        lastTotpStep = row.lastTotpStep,
        failedAttempts = row.failedAttempts,
        lockedAt = row.lockedAt,
        activeRoles = roles(row.activeRoles),
        suspendedRoles = roles(row.suspendedRoles),
        sessionGeneration = row.sessionGeneration,
        securityVersion = row.securityVersion,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
    )

    fun newRow(account: AdminAccount): JpaAdminAccountEntity =
        JpaAdminAccountEntity.newRow(account.id.value, account.loginName.value, account.createdAt).also { copyMutable(account, it) }

    fun copyMutable(account: AdminAccount, row: JpaAdminAccountEntity) {
        row.displayName = account.displayName.value
        row.status = account.status.name
        row.statusBeforeSuspend = account.statusBeforeSuspend?.name
        row.pendingPurpose = account.pending?.purpose?.name
        row.pendingBasis = account.pending?.basis
        row.credentialBasis = account.credentialBasis.basis
        row.credentialBasisRequester = account.credentialBasis.requester?.value
        row.credentialBasisApprover = account.credentialBasis.approver?.value
        row.credentialBasisCarried = account.credentialBasis.carried.map { it.value }.sorted().joinToString(",").ifEmpty { null }
        row.passwordHash = account.passwordHash?.value
        row.totpSecretCiphertext = account.totp?.ciphertext
        row.totpKeyVersion = account.totp?.keyVersion
        row.lastTotpStep = account.lastTotpStep
        row.failedAttempts = account.failedAttempts
        row.lockedAt = account.lockedAt
        row.activeRoles = roleSet(account.activeRoles)
        row.suspendedRoles = roleSet(account.suspendedRoles)
        row.sessionGeneration = account.sessionGeneration
        row.securityVersion = account.securityVersion
        row.updatedAt = account.updatedAt
    }

    fun roleSet(roles: Set<AdminRole>): String = roles.sortedBy { it.ordinal }.joinToString(",") { it.name }

    fun roles(value: String): Set<AdminRole> = value.split(',').filter { it.isNotEmpty() }.map(AdminRole::valueOf).toSet()
}
