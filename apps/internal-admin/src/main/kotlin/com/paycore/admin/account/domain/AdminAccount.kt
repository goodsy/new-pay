package com.paycore.admin.account.domain

import java.time.Instant

/**
 * 관리자 계정 Aggregate(§4.3·§4.5·§4.7.7). 불변 객체 — 전이는 새 계정과 역할 이벤트([AccountChange.Applied]) 또는 업무 거절([AccountChange.Rejected])을 돌려준다.
 *
 * - [sessionGeneration]: 기존 세션 즉시 무효화 값(새 로그인 우선 — D-ADMIN1-001, 잠금·정지·역할 회수·재설정·대기 전이도 +1).
 * - [securityVersion]: 대기 중 승인을 무효로 만드는 값(§4.7.5 사건 표 — 상태 전이·역할 이벤트·자격 변경·등록 코드 발급/폐기는 +1, 인증 활동은 불변).
 * - 현재 활성·정지 역할 집합은 [RoleEvent]와 같은 TX에서 함께 바뀌는 현재값이다(요청마다 PK 단건 판정).
 * - JPA `version`(기술 값)은 여기 없다 — 저장 Port가 따로 다룬다.
 */
class AdminAccount private constructor(
    val id: AdminActorId,
    val loginName: LoginName,
    val displayName: DisplayName,
    val status: AccountStatus,
    val statusBeforeSuspend: AccountStatus?,
    val pending: PendingEnrollment?,
    /** 현재 자격을 만들 코드의 발급 근거와 그 요청자·승인자(D-ADMIN1-014·015 — 권한 상승 배제). */
    val credentialBasis: CredentialBasis,
    val passwordHash: PasswordHash?,
    val totp: TotpCredential?,
    val lastTotpStep: Long?,
    val failedAttempts: Int,
    val lockedAt: Instant?,
    val activeRoles: Set<AdminRole>,
    val suspendedRoles: Set<AdminRole>,
    val sessionGeneration: Long,
    val securityVersion: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(failedAttempts >= 0 && sessionGeneration >= 0 && securityVersion >= 0) { "counters must not be negative" }
        require(lastTotpStep == null || lastTotpStep >= 0) { "TOTP step must not be negative" }
        require(activeRoles.intersect(suspendedRoles).isEmpty()) { "a role cannot be both active and suspended" }
        require(status != AccountStatus.ACTIVE || (passwordHash != null && totp != null)) { "an ACTIVE account needs a password and a TOTP credential" }
        require(status != AccountStatus.LOCKED || (lockedAt != null && passwordHash != null && totp != null)) { "a LOCKED account needs its lock time and credentials" }
        require((status == AccountStatus.SUSPENDED) == (statusBeforeSuspend != null)) { "the previous status exists exactly for SUSPENDED accounts" }
        require(statusBeforeSuspend != AccountStatus.SUSPENDED) { "the previous status cannot be SUSPENDED" }
        require(pending == null || status == AccountStatus.PENDING_ENROLLMENT) { "a pending reason exists only for PENDING_ENROLLMENT accounts" }
        // D-ADMIN1-014: 대기 중인 코드의 근거는 곧 만들 자격의 근거다(코드 발급과 자격 근거는 함께 바뀐다).
        require(pending == null || pending.basis == credentialBasis.basis) { "the pending basis is the credential basis" }
        require(!credentialBasis.isIssuer(id)) { "an account cannot be the issuer of its own credential" }
        // S5(S3 리뷰 m-4): 잠긴 계정을 정지해도 원래 잠금 시각을 보존한다(정지 해제가 LOCKED로 되돌릴 때 같은 시각 — 재활성이 잠금 시각을 지어내지 않는다).
        require(lockedAt == null || status == AccountStatus.LOCKED || statusBeforeSuspend == AccountStatus.LOCKED) { "a lock time exists only for locked accounts" }
        require(statusBeforeSuspend != AccountStatus.LOCKED || lockedAt != null) { "an account suspended while locked keeps its lock time" }
    }

    /** 새 로그인 세션(TOTP 단계 성공 — S4): 세대만 +1(인증 활동이라 security_version 불변). */
    fun startNewSession(now: Instant): AdminAccount = copy(sessionGeneration = sessionGeneration + 1, updatedAt = now)

    /** 전체 로그인 성공: 실패 계수 0(인증 활동 — security_version 불변). */
    fun recordAuthenticationSuccess(now: Instant): AdminAccount = copy(failedAttempts = 0, updatedAt = now)

    /**
     * 인증 실패(비밀번호·로그인 TOTP·재인증 TOTP 합산 — D-ADMIN1-005). ACTIVE만 센다(잠금 TX 안에서 상태를 먼저 판정 — m-4).
     * 한도 번째 실패에서 LOCKED·세대 +1·security_version +1. 자동 해제 없음.
     */
    fun recordAuthenticationFailure(policy: LoginAttemptPolicy, now: Instant): AuthenticationFailure {
        if (status != AccountStatus.ACTIVE || hasReachedFailureLimit(policy)) return AuthenticationFailure.NotCounted(this)
        val failures = failedAttempts + 1
        if (failures < policy.maxFailures) return AuthenticationFailure.Counted(copy(failedAttempts = failures, updatedAt = now))
        return AuthenticationFailure.Locked(
            copy(
                status = AccountStatus.LOCKED, failedAttempts = failures, lockedAt = now,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
        )
    }

    /** 실패 한도 도달(설정 한도가 낮아져 ACTIVE인데 계수가 이미 한도 이상인 경우 포함 — §4.3.2(b) 선판정). */
    fun hasReachedFailureLimit(policy: LoginAttemptPolicy): Boolean = failedAttempts >= policy.maxFailures

    /** TOTP 단계 소비(로그인·재인증 공통, 계정 행 잠금 아래 — §4.3.4): 마지막 단계보다 뒤만. 인증 활동이라 security_version 불변. */
    fun acceptTotpStep(step: Long, now: Instant): AccountChange {
        require(step >= 0) { "TOTP step must not be negative" }
        if (lastTotpStep != null && step <= lastTotpStep) return AccountChange.Rejected(AccountRejection.TOTP_STEP_REUSED)
        return AccountChange.Applied(copy(lastTotpStep = step, updatedAt = now))
    }

    /** 정지(1인 즉시 — D-ADMIN1-003). 본인 정지 거부(n-E). 대기 사유를 비운다(H-B — 미사용 코드 폐기는 같은 TX의 application). */
    fun suspend(requester: AdminActorId, now: Instant): AccountChange {
        if (requester == id) return AccountChange.Rejected(AccountRejection.SELF_SUSPEND)
        if (status == AccountStatus.SUSPENDED) return AccountChange.Rejected(AccountRejection.ALREADY_SUSPENDED)
        return AccountChange.Applied(
            copy(
                status = AccountStatus.SUSPENDED, statusBeforeSuspend = status, pending = null,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
        )
    }

    /** `ACCOUNT_UNLOCK` 승인 실행(§4.7.6): LOCKED → ACTIVE, 계수 0, 잠금 시각 비움, 세대·security_version +1. */
    fun unlock(now: Instant): AccountChange {
        if (status != AccountStatus.LOCKED) return AccountChange.Rejected(AccountRejection.NOT_ELIGIBLE)
        return AccountChange.Applied(
            copy(status = AccountStatus.ACTIVE, failedAttempts = 0, lockedAt = null, sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now),
        )
    }

    /**
     * `ACCOUNT_REACTIVATE` 승인 실행(정지 해제 — §4.3.1·§4.7.6): SUSPENDED → 정지 직전 상태. 직전이 LOCKED면 **원래 잠금 시각** 그대로(S3 리뷰 m-4),
     * 직전이 PENDING_ENROLLMENT면 대기 사유 없이 돌아간다(정지가 코드를 폐기했으므로 새 코드 발급 전 등록 불가 — H-B). 정지 역할은 그대로. 세대·security_version +1.
     */
    fun reactivate(now: Instant): AccountChange {
        val previous = statusBeforeSuspend ?: return AccountChange.Rejected(AccountRejection.NOT_ELIGIBLE)
        return AccountChange.Applied(
            copy(status = previous, statusBeforeSuspend = null, sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now),
        )
    }

    /**
     * `ROLE_REACTIVATE` 승인 실행(§4.7.7): ACTIVE(재등록 완료) 계정에서 원인 재설정 [causeApprovalId]로 정지된 역할([suspendedByCause] — 역할 이벤트 이력으로
     * application이 계산)만 되살린다. 그 사이 회수된 역할은 정지 집합에 없으므로 복원되지 않는다. 세대·security_version +1.
     */
    fun reactivateRoles(causeApprovalId: String, suspendedByCause: Set<AdminRole>, approver: AdminActorId, approvalId: String, now: Instant): AccountChange {
        if (status != AccountStatus.ACTIVE) return AccountChange.Rejected(AccountRejection.NOT_ELIGIBLE)
        val roles = suspendedByCause.intersect(suspendedRoles)
        if (roles.isEmpty()) return AccountChange.Rejected(AccountRejection.NOTHING_TO_REACTIVATE)
        val events = roles.sortedBy { it.ordinal }.map { RoleEvent(id, it, RoleEventType.REACTIVATE, RoleEventActor.Admin(approver), approvalId, causeApprovalId, now) }
        return AccountChange.Applied(
            copy(
                activeRoles = activeRoles + roles, suspendedRoles = suspendedRoles - roles,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
            events,
        )
    }

    /** 역할 회수(1인 즉시 — D-ADMIN1-004). 활성이든 정지든 회수하면 다시 재활성되지 않는다. 본인 역할 변경 거부. */
    fun revokeRole(requester: AdminActorId, role: AdminRole, now: Instant): AccountChange {
        if (requester == id) return AccountChange.Rejected(AccountRejection.SELF_ROLE_CHANGE)
        if (role !in activeRoles && role !in suspendedRoles) return AccountChange.Rejected(AccountRejection.ROLE_NOT_HELD)
        val event = RoleEvent(id, role, RoleEventType.REVOKE, RoleEventActor.Admin(requester), approvalId = null, cause = null, occurredAt = now)
        return AccountChange.Applied(
            copy(
                activeRoles = activeRoles - role, suspendedRoles = suspendedRoles - role,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
            listOf(event),
        )
    }

    /**
     * 역할 부여(GRANT). 부트스트랩(§4.9.1, [RoleEventActor.SystemBootstrap])과 S5 `ROLE_GRANT` 승인 실행이 쓴다 — 권한 판정(누가 부여할 수 있나)은 호출자가 한다.
     * 이미 활성이거나 정지된 역할은 부여하지 않는다(정지 역할은 재활성 승인으로만 — §4.7.7).
     */
    fun grantRole(role: AdminRole, actor: RoleEventActor, approvalId: String?, now: Instant): AccountChange {
        if (status == AccountStatus.SUSPENDED) return AccountChange.Rejected(AccountRejection.NOT_ELIGIBLE)
        if (role in activeRoles || role in suspendedRoles) return AccountChange.Rejected(AccountRejection.ROLE_ALREADY_HELD)
        val event = RoleEvent(id, role, RoleEventType.GRANT, actor, approvalId, cause = null, occurredAt = now)
        return AccountChange.Applied(
            copy(activeRoles = activeRoles + role, sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now),
            listOf(event),
        )
    }

    /**
     * 자격 재설정·재등록 실행(D-ADMIN1-009 — `PASSWORD_RESET`·`TOTP_REENROLL` 승인 TX). ACTIVE·LOCKED, 그리고 **PENDING_ENROLLMENT**(rev 0.4 m-2 — 2인 승인
     * 코드 재발급: 근거를 새 approvalId로 교체). 대기 사유 = 목적·근거 = 승인 ID, 해당 자격 무효화, 활성 역할 전부 SUSPEND(원인 = 승인 ID, 이력 보존), 세대·security_version +1.
     * (rev 0.5 m-1) 확정 자격(비밀번호 해시·TOTP 암호문) 중 하나라도 없으면 종류와 관계없이 대기 사유를 `ENROLL`(근거 = 이 approvalId)로 둬 두 요소를 모두 설정하게 한다.
     */
    fun beginCredentialReset(purpose: EnrollmentPurpose, approvalId: String, requester: AdminActorId, executor: AdminActorId, now: Instant): AccountChange {
        require(purpose == EnrollmentPurpose.PASSWORD_RESET || purpose == EnrollmentPurpose.TOTP_REENROLL) { "credential reset purpose must be PASSWORD_RESET or TOTP_REENROLL" }
        if (status == AccountStatus.SUSPENDED) return AccountChange.Rejected(AccountRejection.NOT_ELIGIBLE)
        val effective = if (passwordHash == null || totp == null) EnrollmentPurpose.ENROLL else purpose
        // D-ADMIN1-013: 이미 정지된 역할에도 원인 = 이 재설정 SUSPEND를 남긴다(정지 원인 = 최신 SUSPEND 원인 — rev 0.5 n-2 재생 규칙). 재활성은 최신 재설정만 원인으로.
        val events = (activeRoles + suspendedRoles).sortedBy { it.ordinal }.map {
            RoleEvent(id, it, RoleEventType.SUSPEND, RoleEventActor.Admin(executor), approvalId, cause = approvalId, occurredAt = now)
        }
        return AccountChange.Applied(
            copy(
                status = AccountStatus.PENDING_ENROLLMENT, pending = PendingEnrollment(effective, approvalId),
                credentialBasis = CredentialBasis.approval(approvalId, requester, executor),
                passwordHash = if (effective.setsPassword) null else passwordHash,
                totp = if (effective.setsTotp) null else totp,
                lastTotpStep = if (effective.setsTotp) null else lastTotpStep,
                failedAttempts = 0, lockedAt = null,
                activeRoles = emptySet(), suspendedRoles = suspendedRoles + activeRoles,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
            events,
        )
    }

    /** 복구 시점에 이 계정이 보유한(활성 또는 정지) SECURITY_ADMIN·APPROVER — 작업 A 코드 행에 기록한다(§4.9.2, D-ADMIN1-012). */
    val recoveryRecordableRoles: Set<AdminRole> get() = (activeRoles + suspendedRoles).intersect(RECOVERY_ROLES)

    /**
     * 오프라인 복구 작업 A(§4.9.2 — 시스템 경로, 호스트 운영자 신뢰 경계 n-H): 대기 사유 RECOVERY·근거 [episodeBasis], 비밀번호·TOTP 모두 무효화,
     * 활성 역할 전부와 **이미 정지된 역할 전부**에 원인 = 회차 SUSPEND(rev 0.4 m-1·D-99 — 정지 원인 = 최신 SUSPEND), 자격 근거 = 시스템(관리자 배제 없음),
     * 세대·security_version +1. SUSPENDED 계정은 대상이 아니다(복구는 정지 해제가 아니다 — fail-closed).
     */
    fun beginRecovery(episodeBasis: String, now: Instant): AccountChange {
        if (status == AccountStatus.SUSPENDED) return AccountChange.Rejected(AccountRejection.NOT_ELIGIBLE)
        val events = (activeRoles + suspendedRoles).sortedBy { it.ordinal }.map {
            RoleEvent(id, it, RoleEventType.SUSPEND, RoleEventActor.SystemRecovery, approvalId = null, cause = episodeBasis, occurredAt = now)
        }
        return AccountChange.Applied(
            copy(
                status = AccountStatus.PENDING_ENROLLMENT, pending = PendingEnrollment(EnrollmentPurpose.RECOVERY, episodeBasis),
                credentialBasis = CredentialBasis.system(episodeBasis), passwordHash = null, totp = null, lastTotpStep = null, failedAttempts = 0, lockedAt = null,
                activeRoles = emptySet(), suspendedRoles = suspendedRoles + activeRoles,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
            events,
        )
    }

    /**
     * 복구 코드 등록 완료의 자동 재활성(§4.9.2): ACTIVE 계정에서 [roles](완료 TX가 회차 잠금 아래 조건을 확인한 기록 역할) 중 아직 정지된 것만 `SYSTEM_RECOVERY`가
     * 되살린다(원인 = 회차). 세대·security_version +1.
     */
    fun reactivateRecoveredRoles(roles: Set<AdminRole>, episodeBasis: String, now: Instant): AccountChange {
        if (status != AccountStatus.ACTIVE) return AccountChange.Rejected(AccountRejection.NOT_ELIGIBLE)
        val restored = roles.intersect(suspendedRoles)
        if (restored.isEmpty()) return AccountChange.Rejected(AccountRejection.NOTHING_TO_REACTIVATE)
        val events = restored.sortedBy { it.ordinal }.map { RoleEvent(id, it, RoleEventType.REACTIVATE, RoleEventActor.SystemRecovery, null, episodeBasis, now) }
        return AccountChange.Applied(
            copy(
                activeRoles = activeRoles + restored, suspendedRoles = suspendedRoles - restored,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
            events,
        )
    }

    /**
     * `ENROLL` 코드 재발급(1인 — rev 0.4 H-1): 활성·정지 역할이 하나도 없는 대기 계정만. 근거를 새 발급 ID로 바꾼다(코드 발급 = security_version +1).
     * 자격 발급 근거도 이 명령(수행자 [issuer] = 코드 수령자 — D-ADMIN1-014·015)으로 바뀐다.
     */
    fun reissueEnrollCode(basis: String, issuer: AdminActorId, now: Instant): AccountChange {
        if (status != AccountStatus.PENDING_ENROLLMENT) return AccountChange.Rejected(AccountRejection.NOT_PENDING_ENROLLMENT)
        if (activeRoles.isNotEmpty() || suspendedRoles.isNotEmpty()) return AccountChange.Rejected(AccountRejection.ROLE_BEARING_ACCOUNT)
        // D-98(재리뷰 m-2): 재발급은 자격을 바꾸지 않는다 — 등록 완료 전까지 이전 발급자들도 배제에 남긴다(상한을 넘으면 거절 — fail-closed).
        if ((credentialBasis.issuers - issuer).size > CredentialBasis.MAX_CARRIED) return AccountChange.Rejected(AccountRejection.REISSUE_ISSUER_LIMIT)
        return AccountChange.Applied(
            copy(
                pending = PendingEnrollment(EnrollmentPurpose.ENROLL, basis), credentialBasis = credentialBasis.reissuedBy(basis, issuer),
                securityVersion = securityVersion + 1, updatedAt = now,
            ),
        )
    }

    /**
     * 등록 완료(§4.3.6 완료 TX — 계정 행 잠금 아래). 상태가 PENDING_ENROLLMENT이고 대기 사유·근거가 코드의 목적·근거와 같을 때만(H-B), 목적이 정한 요소만
     * 받는다(D-ADMIN1-011). ACTIVE·대기 사유 비움·실패 계수 0·세대·security_version +1. 정지된 역할은 그대로(재활성은 별도 승인 — §4.7.7).
     */
    fun completeEnrollment(code: PendingEnrollment, newPassword: PasswordHash?, newTotp: NewTotp?, now: Instant): AccountChange {
        if (status != AccountStatus.PENDING_ENROLLMENT || pending != code) return AccountChange.Rejected(AccountRejection.ENROLLMENT_NOT_APPLICABLE)
        if ((newPassword != null) != code.purpose.setsPassword || (newTotp != null) != code.purpose.setsTotp) {
            return AccountChange.Rejected(AccountRejection.ENROLLMENT_STEP_INVALID)
        }
        val password = newPassword ?: passwordHash
        val credential = newTotp?.credential ?: totp
        if (password == null || credential == null) return AccountChange.Rejected(AccountRejection.ENROLLMENT_NOT_APPLICABLE)
        return AccountChange.Applied(
            copy(
                status = AccountStatus.ACTIVE, pending = null, passwordHash = password, totp = credential, credentialBasis = credentialBasis.settledOnEnrollment(),
                lastTotpStep = newTotp?.confirmedStep ?: lastTotpStep, failedAttempts = 0, lockedAt = null,
                sessionGeneration = sessionGeneration + 1, securityVersion = securityVersion + 1, updatedAt = now,
            ),
        )
    }

    @Suppress("LongParameterList")
    private fun copy(
        status: AccountStatus = this.status,
        statusBeforeSuspend: AccountStatus? = this.statusBeforeSuspend,
        pending: PendingEnrollment? = this.pending,
        credentialBasis: CredentialBasis = this.credentialBasis,
        passwordHash: PasswordHash? = this.passwordHash,
        totp: TotpCredential? = this.totp,
        lastTotpStep: Long? = this.lastTotpStep,
        failedAttempts: Int = this.failedAttempts,
        lockedAt: Instant? = this.lockedAt,
        activeRoles: Set<AdminRole> = this.activeRoles,
        suspendedRoles: Set<AdminRole> = this.suspendedRoles,
        sessionGeneration: Long = this.sessionGeneration,
        securityVersion: Long = this.securityVersion,
        updatedAt: Instant = this.updatedAt,
    ) = AdminAccount(
        id, loginName, displayName, status, if (status == AccountStatus.SUSPENDED) statusBeforeSuspend else null, pending, credentialBasis, passwordHash, totp, lastTotpStep,
        failedAttempts, if (status == AccountStatus.LOCKED || (status == AccountStatus.SUSPENDED && statusBeforeSuspend == AccountStatus.LOCKED)) lockedAt else null, activeRoles.toSet(), suspendedRoles.toSet(), sessionGeneration, securityVersion,
        createdAt, updatedAt,
    )

    override fun equals(other: Any?): Boolean = other is AdminAccount && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "AdminAccount(id=$id, status=$status, roles=$activeRoles)"

    companion object {
        val RECOVERY_ROLES: Set<AdminRole> = setOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)

        /**
         * 계정 생성(역할 없음 — 1인 SECURITY_ADMIN, D-ADMIN1-003). 대기 사유 ENROLL·근거 = 등록 코드 발급 ID([credentialBasis]의 근거와 같다 —
         * 1인 생성이면 생성자가 코드 수령자로 배제 대상, D-ADMIN1-015).
         */
        fun create(id: AdminActorId, loginName: LoginName, displayName: DisplayName, credentialBasis: CredentialBasis, now: Instant): AdminAccount = AdminAccount(
            id, loginName, displayName, AccountStatus.PENDING_ENROLLMENT, null, PendingEnrollment(EnrollmentPurpose.ENROLL, credentialBasis.basis), credentialBasis, null, null, null,
            0, null, emptySet(), emptySet(), 0, 0, now, now,
        )

        /** 저장 행 복원. 불가능한 조합(ACTIVE인데 자격 없음 등)은 거부한다(init). */
        @Suppress("LongParameterList")
        fun reconstitute(
            id: AdminActorId,
            loginName: LoginName,
            displayName: DisplayName,
            status: AccountStatus,
            statusBeforeSuspend: AccountStatus?,
            pending: PendingEnrollment?,
            credentialBasis: CredentialBasis,
            passwordHash: PasswordHash?,
            totp: TotpCredential?,
            lastTotpStep: Long?,
            failedAttempts: Int,
            lockedAt: Instant?,
            activeRoles: Set<AdminRole>,
            suspendedRoles: Set<AdminRole>,
            sessionGeneration: Long,
            securityVersion: Long,
            createdAt: Instant,
            updatedAt: Instant,
        ): AdminAccount = AdminAccount(
            id, loginName, displayName, status, statusBeforeSuspend, pending, credentialBasis, passwordHash, totp, lastTotpStep, failedAttempts, lockedAt, activeRoles.toSet(),
            suspendedRoles.toSet(), sessionGeneration, securityVersion, createdAt, updatedAt,
        )
    }
}

/** 등록 완료에서 새로 설정하는 TOTP(암호문)와 확인 코드의 단계(이후 그 단계 이하는 재사용 거부). */
data class NewTotp(val credential: TotpCredential, val confirmedStep: Long)

/** 계정 전이 결과. 거절은 상태 변경 없음. */
sealed interface AccountChange {
    data class Applied(val account: AdminAccount, val events: List<RoleEvent> = emptyList()) : AccountChange
    data class Rejected(val reason: AccountRejection) : AccountChange
}

/** 인증 실패 기록 결과. */
sealed interface AuthenticationFailure {
    val account: AdminAccount

    data class Counted(override val account: AdminAccount) : AuthenticationFailure
    data class Locked(override val account: AdminAccount) : AuthenticationFailure
    data class NotCounted(override val account: AdminAccount) : AuthenticationFailure
}

/** 계정 전이 거절 사유(고정 code). */
enum class AccountRejection {
    SELF_SUSPEND,
    ALREADY_SUSPENDED,
    SELF_ROLE_CHANGE,
    ROLE_NOT_HELD,
    ROLE_ALREADY_HELD,
    NOTHING_TO_REACTIVATE,
    ROLE_BEARING_ACCOUNT,
    NOT_PENDING_ENROLLMENT,
    NOT_ELIGIBLE,
    ENROLLMENT_NOT_APPLICABLE,
    ENROLLMENT_STEP_INVALID,
    TOTP_STEP_REUSED,
    REISSUE_ISSUER_LIMIT,
}
