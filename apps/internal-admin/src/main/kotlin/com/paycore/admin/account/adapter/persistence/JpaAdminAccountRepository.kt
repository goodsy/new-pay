package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.adapter.persistence.PersistenceFailures.translate
import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.PersistenceFailure
import com.paycore.admin.account.application.required.Versioned
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.LoginName
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [AdminAccountRepository] JPA 구현. 새 행은 `persist`만(merge 금지), 갱신은 잠근 managed Entity의 updatable 열만 dirty checking으로(`@Version` 조건 포함),
 * 쓰기 끝에 flush해 제약 위반·version 충돌을 Port 안에서 [PersistenceFailure]로 드러낸다(flush ≠ commit).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAdminAccountRepository(private val entityManager: EntityManager) : AdminAccountRepository {

    override fun find(id: AdminActorId): Versioned<AdminAccount>? = translate(ENTITY) {
        entityManager.find(JpaAdminAccountEntity::class.java, id.value)?.let(::versioned)
    }

    override fun findByLoginName(loginName: LoginName): Versioned<AdminAccount>? = translate(ENTITY) {
        entityManager.createQuery("select a from JpaAdminAccountEntity a where a.loginName = :login", JpaAdminAccountEntity::class.java)
            .setParameter("login", loginName.value).resultList.singleOrNull()?.let(::versioned)
    }

    override fun lock(id: AdminActorId): Versioned<AdminAccount>? = translate(ENTITY) {
        entityManager.lockRow(JpaAdminAccountEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lockAll(ids: Collection<AdminActorId>): Map<AdminActorId, Versioned<AdminAccount>> = translate(ENTITY) {
        // 전역 잠금 순서(§4.13): actor ID 오름차순으로 한 행씩 잠근다.
        ids.distinct().sorted().mapNotNull { id -> entityManager.lockRow(JpaAdminAccountEntity::class.java, id.value)?.let { id to versioned(it) } }.toMap()
    }

    override fun insert(account: AdminAccount): Versioned<AdminAccount> = translate(ENTITY) {
        val row = AdminAccountPersistenceMapper.newRow(account)
        entityManager.persist(row)
        entityManager.flush()
        versioned(row)
    }

    override fun update(current: Versioned<AdminAccount>, next: AdminAccount): Versioned<AdminAccount> = translate(ENTITY) {
        require(current.value.id == next.id) { "an update keeps the actor id" }
        val row = entityManager.find(JpaAdminAccountEntity::class.java, next.id.value) ?: throw PersistenceFailure.OptimisticConflict(ENTITY)
        if (row.version != current.version) throw PersistenceFailure.OptimisticConflict(ENTITY)
        AdminAccountPersistenceMapper.copyMutable(next, row)
        entityManager.flush()
        versioned(row)
    }

    override fun anyExists(): Boolean = translate(ENTITY) {
        entityManager.createNativeQuery("SELECT 1 FROM admin_account LIMIT 1").resultList.isNotEmpty()
    }

    override fun countActiveHolders(role: AdminRole): Int = translate(ENTITY) {
        // 역할 집합은 MySQL SET — FIND_IN_SET로 판정(복구 조건·자동 재활성은 회차 행 잠금 아래에서 부르고, 활성 역할 gauge는 잠금 없는 읽기 전용 근사로 부른다).
        (entityManager.createNativeQuery("SELECT COUNT(*) FROM admin_account WHERE status = 'ACTIVE' AND FIND_IN_SET(?1, active_roles) > 0")
            .setParameter(1, role.name).singleResult as Number).toInt()
    }

    private fun versioned(row: JpaAdminAccountEntity) = Versioned(AdminAccountPersistenceMapper.toDomain(row), requireNotNull(row.version))

    private companion object {
        const val ENTITY = "admin_account"
    }
}
