package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.adapter.persistence.PersistenceFailures.translate
import com.paycore.admin.account.application.required.BootstrapStore
import com.paycore.admin.account.domain.AdminActorId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** [BootstrapStore] — 고정 행 `admin_bootstrap(id = 1)` native INSERT(즉시 실행 — PK 1062 → `UniqueConflict(BOOTSTRAP)`). append-only(UPDATE·DELETE 권한 없음). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBootstrapStore(private val entityManager: EntityManager) : BootstrapStore {
    override fun markExecuted(executedAt: Instant, securityAdmin: AdminActorId, approver: AdminActorId): Unit = translate("admin_bootstrap") {
        entityManager.createNativeQuery(
            "INSERT INTO admin_bootstrap (id, executed_at, security_admin_actor_id, approver_actor_id) VALUES (1, ?1, ?2, ?3)",
        ).setParameter(1, executedAt).setParameter(2, securityAdmin.value).setParameter(3, approver.value).executeUpdate()
    }
}
