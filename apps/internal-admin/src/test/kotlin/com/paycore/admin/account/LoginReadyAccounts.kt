package com.paycore.admin.account

import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.application.required.CipherContext
import com.paycore.admin.account.application.required.PasswordHasher
import com.paycore.admin.account.application.required.TotpSecretCipher
import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.NewTotp
import com.paycore.admin.account.domain.PendingEnrollment
import com.paycore.admin.account.domain.RoleEvent
import com.paycore.admin.account.domain.RoleEventActor
import com.paycore.admin.account.domain.RoleEventType
import com.paycore.admin.account.domain.TotpCredential
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 로그인 가능한 계정(Named Fixture — 저장 측). [AdminAccountFixture]와 같은 domain 공식 전이(생성 → 역할 부여 → ENROLL 완료)로 만들되, **실제** BCrypt 해시와 실제
 * 암호화 TOTP 시크릿(저장 문맥 AAD)을 넣어 HTTP 2단계 로그인을 끝까지 할 수 있게 한다. 역할 규칙은 [AdminAccountFixture]와 같다(부트스트랩 = SA·APPROVER 단일,
 * 그 밖은 실제 2인 승인 경로 `ApprovalPath`로 받는다).
 */
@Component
class LoginReadyAccounts(
    private val accounts: AdminAccountRepository,
    private val roleEvents: AdminRoleEventStore,
    private val hasher: PasswordHasher,
    private val cipher: TotpSecretCipher,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    class LoginReady(val account: AdminAccount, val password: String, private val secretBytes: ByteArray) {
        val loginName: String get() = account.loginName.value
        val secret: ByteArray get() = secretBytes.copyOf()
        override fun toString(): String = "LoginReady(${account.id})"
    }

    /** [login]을 주면 그 로그인 이름(설정에 미리 적는 이름이 필요한 시험 — ADMIN-DEMO-LOGIN-001), 없으면 actor ID에서 만든 이름. */
    fun withoutRoles(password: String? = null, login: String? = null): LoginReady = create(emptyList(), RoleEventActor.SystemBootstrap, null, password, login)

    fun bootstrapped(role: AdminRole, login: String? = null): LoginReady {
        require(role in AdminAccountFixture.BOOTSTRAP_ROLES) { "bootstrap creates only SECURITY_ADMIN or APPROVER accounts" }
        return create(listOf(role), RoleEventActor.SystemBootstrap, null, login = login)
    }

    private fun create(roles: List<AdminRole>, actor: RoleEventActor, approvalId: String?, fixedPassword: String? = null, login: String? = null): LoginReady {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        // 부트스트랩 역할 계정은 시스템 경로(배제 대상 없음), 역할 없는 계정은 1인 생성 경로(Fixture 생성자 — D-ADMIN1-015).
        val creator = if (actor == RoleEventActor.SystemBootstrap && roles.isNotEmpty()) null else AdminAccountFixture.FIXTURE_CREATOR
        var account = if (login == null) AdminAccountFixture.pending(creator = creator) else AdminAccountFixture.pending(login = login, creator = creator)
        val events = mutableListOf<RoleEvent>()
        for (role in roles) {
            val change = account.grantRole(role, actor, approvalId, now) as AccountChange.Applied
            account = change.account
            events += change.events
        }
        val password = fixedPassword ?: ("pw-" + random.nextLong().toULong().toString(36).padStart(14, 'x'))
        val secret = ByteArray(20).also(random::nextBytes)
        val sealed = cipher.encrypt(secret, CipherContext(CipherContext.STORED_TOTP, listOf(account.id.value)))
        account = (
            account.completeEnrollment(
                PendingEnrollment(EnrollmentPurpose.ENROLL, account.pending!!.basis), hasher.hash(password.toCharArray()),
                NewTotp(TotpCredential(sealed.bytes, sealed.keyVersion), confirmedStep = 0), now,
            ) as AccountChange.Applied
            ).account
        val saved = tx.execute {
            val inserted = accounts.insert(account).value
            roleEvents.append(events.map { RoleEvent(it.target, it.role, RoleEventType.GRANT, it.actor, it.approvalId, null, it.occurredAt) })
            inserted
        }
        return LoginReady(saved, password, secret)
    }
}
