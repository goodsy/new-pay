package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.adapter.persistence.PersistenceFailures.translate
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.EnrollmentPurpose
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [EnrollmentCodeStore] JPA 구현. 소비·폐기는 조건부 JPQL UPDATE(SET은 used_at 또는 revoked_at 하나 — 열 단위 권한 안). 원자 소비의 승패는 영향 행 수로만 판단한다.
 * JPQL 대량 UPDATE는 영속성 컨텍스트를 거치지 않으므로, [find]는 managed Entity를 `refresh`해 같은 TX의 대량 UPDATE 뒤에도 DB 값을 돌려준다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEnrollmentCodeStore(private val entityManager: EntityManager) : EnrollmentCodeStore {

    override fun insert(code: EnrollmentCode): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaEnrollmentCodeEntity.newRow(
                code.hash.bytes, code.target.value, code.purpose.name, code.basis, code.recoveryEpisode,
                code.recoveryRoles?.let(AdminAccountPersistenceMapper::roleSet), code.issuedAt, code.expiresAt,
            ),
        )
        entityManager.flush()
    }

    override fun find(hash: EnrollmentCodeHash): EnrollmentCode? = translate(ENTITY) {
        entityManager.find(JpaEnrollmentCodeEntity::class.java, JpaEnrollmentCodeKey(hash.bytes))?.let { row ->
            entityManager.refresh(row)
            EnrollmentCode(
                hash = EnrollmentCodeHash(row.key.codeHash),
                target = AdminActorId(row.targetActorId),
                purpose = EnrollmentPurpose.valueOf(row.purpose),
                basis = row.basis,
                recoveryEpisode = row.recoveryEpisodeNo,
                recoveryRoles = row.recoveryRoles?.let(AdminAccountPersistenceMapper::roles),
                issuedAt = row.issuedAt,
                expiresAt = row.expiresAt,
                usedAt = row.usedAt,
                revokedAt = row.revokedAt,
            )
        }
    }

    override fun consume(hash: EnrollmentCodeHash, now: Instant): Boolean = translate(ENTITY) {
        entityManager.createQuery(
            "update JpaEnrollmentCodeEntity c set c.usedAt = :now " +
                "where c.key = :key and c.usedAt is null and c.revokedAt is null and c.expiresAt > :now",
        ).setParameter("now", now).setParameter("key", JpaEnrollmentCodeKey(hash.bytes)).executeUpdate() == 1
    }

    override fun revokeUnused(target: AdminActorId, now: Instant): List<String> = translate(ENTITY) {
        val bases = entityManager.createQuery(
            "select c.basis from JpaEnrollmentCodeEntity c where c.targetActorId = :target and c.usedAt is null and c.revokedAt is null order by c.issuedAt",
            String::class.java,
        ).setParameter("target", target.value).resultList
        val revoked = entityManager.createQuery(
            "update JpaEnrollmentCodeEntity c set c.revokedAt = :now where c.targetActorId = :target and c.usedAt is null and c.revokedAt is null",
        ).setParameter("now", now).setParameter("target", target.value).executeUpdate()
        check(revoked == bases.size) { "revoked code count changed under the target account lock" }
        bases
    }

    override fun countUsableForEpisode(episodeNo: Long, now: Instant): Int = translate(ENTITY) {
        entityManager.createQuery(
            "select count(c) from JpaEnrollmentCodeEntity c where c.recoveryEpisodeNo = :episode and c.usedAt is null and c.revokedAt is null and c.expiresAt > :now",
            Long::class.javaObjectType,
        ).setParameter("episode", episodeNo).setParameter("now", now).singleResult.toInt()
    }

    private companion object {
        const val ENTITY = "admin_enrollment_code"
    }
}
