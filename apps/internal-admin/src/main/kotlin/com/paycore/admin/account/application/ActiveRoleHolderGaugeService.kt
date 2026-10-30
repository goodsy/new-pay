package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.RefreshActiveRoleHoldersUseCase
import com.paycore.admin.account.application.required.AdminAccountMetrics
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminClock
import com.paycore.admin.account.domain.AdminRole
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

/**
 * [RefreshActiveRoleHoldersUseCase](D-ADMIN1-023). **읽기 전용** 로컬 TX 하나에서 잠금 없이 역할별 활성 보유자 수를 세고(지표용 근사 — 권위 판정은 각 명령 TX),
 * **TX가 끝난 뒤** 지표에 쓴다(AdminAccountMetrics 호출 시점 규칙).
 * 성공이면 gauge·성공 counter·마지막 성공 시각을 갱신하고, 실패면 실패 counter만 올린 뒤 예외를 그대로 올린다 — gauge는 직전 값으로 남고
 * 마지막 성공 이후 경과 gauge가 계속 늘어 "갱신 정체" 경보가 된다(리뷰 Minor-2). 호출자(job)가 실패를 기록한다.
 */
@Service
class ActiveRoleHolderGaugeService(
    transactionManager: PlatformTransactionManager,
    private val accounts: AdminAccountRepository,
    private val metrics: AdminAccountMetrics,
    private val clock: AdminClock,
) : RefreshActiveRoleHoldersUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1, readOnly = true)

    override fun refresh(): Map<String, Int> {
        val counts = try {
            transactions.execute({ false }) { FLOOR_ROLES.associate { it.name to accounts.countActiveHolders(it) } }
        } catch (e: RuntimeException) {
            metrics.activeRoleRefresh(FAILURE)
            throw e
        }
        counts.forEach { (role, count) -> metrics.activeRoleHolders(role, count) }
        metrics.activeRoleRefresh(SUCCESS)
        metrics.activeRoleRefreshSucceeded(clock.now())
        return counts
    }

    private companion object {
        /** LIVE Gate 운영 하한 대상 역할(D-ADMIN1-023). */
        val FLOOR_ROLES = listOf(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
        const val SUCCESS = "SUCCESS"
        const val FAILURE = "FAILURE"
    }
}
