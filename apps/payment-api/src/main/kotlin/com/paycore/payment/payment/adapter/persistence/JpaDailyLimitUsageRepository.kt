package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.DailyLimitUsageRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.DailyLimitUsage
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.LimitExcess
import com.paycore.payment.payment.domain.LimitReservation
import com.paycore.payment.payment.domain.LimitReservationState
import com.paycore.payment.payment.domain.LimitUsageKey
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.MerchantAccountId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [DailyLimitUsageRepository] JPA 구현(AUTH-001 명세 §7.5). 최초 생성은 native `INSERT … ON DUPLICATE KEY UPDATE version = version`(행이 있으면 그 잠금만 기다린다 —
 * SELECT 후 INSERT가 아니라 동시 생성이 UNIQUE 충돌 없이 한 행으로 수렴)이고 그 뒤 `lockRow`(`SELECT … FOR UPDATE`)로 잠가 읽는다. 같은 MID·날짜 승인 시작들은 이 행에서
 * 줄을 서고 합계 불변식은 domain이 판정한다(서로 다른 승인 행 사이 합계는 행별 version으로 지킬 수 없다). 점유 행은 지우지 않는다(취소 미복원 — D-AUTH-007).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaDailyLimitUsageRepository(
    private val usages: DailyLimitUsageJpaRepository,
    private val reservations: LimitReservationJpaRepository,
    private val entityManager: EntityManager,
) : DailyLimitUsageRepository {

    override fun lockOrCreate(key: LimitUsageKey, at: Instant): Versioned<DailyLimitUsage> = translate(ENTITY) {
        val columns = columnsOf(key)
        usages.insertIfAbsent(columns.environment, columns.merchantAccountId, columns.currency, columns.businessDate, at)
        val row = checkNotNull(entityManager.lockRow(JpaDailyLimitUsageEntity::class.java, columns)) { "daily limit usage must exist after insert-if-absent" }
        Versioned(toDomain(row), checkNotNull(row.version))
    }

    override fun update(usage: DailyLimitUsage, expectedVersion: Long, at: Instant): Long = translate(ENTITY) {
        val columns = columnsOf(usage.key)
        val row = entityManager.find(JpaDailyLimitUsageEntity::class.java, columns) ?: error("daily limit usage ${usage.key} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        val stored = reservations.findByUsage(columns.environment, columns.merchantAccountId, columns.currency, columns.businessDate).associateBy { it.authorizationId }
        check(stored.keys.all { id -> usage.reservations.any { it.authorizationId.value == id } }) { "limit reservations are never removed" }
        var changed = false
        usage.reservations.forEachIndexed { i, r ->
            val row0 = stored[r.authorizationId.value]
            if (row0 == null) {
                entityManager.persist(JpaLimitReservationEntity.newRow(columns, r.authorizationId.value, r.amountMinor, r.state.name, r.excess?.name, i, at))
                changed = true
            } else {
                check(row0.amountMinor == r.amountMinor && row0.seq == i) { "limit reservation ${r.authorizationId.value} does not match its stored identity" }
                if (row0.state != r.state.name || row0.excess != r.excess?.name) {
                    row0.state = r.state.name
                    row0.excess = r.excess?.name
                    row0.updatedAt = at
                    changed = true
                }
            }
        }
        if (changed) {
            row.updatedAt = at
            // 루트 열이 updated_at뿐이어도 같은 시각이면 UPDATE가 없을 수 있다 — 점유가 바뀌면 version을 반드시 올린다.
            entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        }
        entityManager.flush()
        checkNotNull(row.version)
    }

    private fun toDomain(row: JpaDailyLimitUsageEntity): DailyLimitUsage {
        val k = row.key
        val key = LimitUsageKey(Environment.valueOf(k.environment), MerchantAccountId(k.merchantAccountId), CurrencyCode(k.currency), k.businessDate)
        return DailyLimitUsage.reconstitute(
            key,
            reservations.findByUsage(k.environment, k.merchantAccountId, k.currency, k.businessDate).map {
                LimitReservation(AuthorizationId(it.authorizationId), it.amountMinor, LimitReservationState.valueOf(it.state), it.excess?.let(LimitExcess::valueOf))
            },
        )
    }

    private fun columnsOf(key: LimitUsageKey) = LimitUsageKeyColumns.of(key.environment.name, key.merchantAccountId.value, key.currency.value, key.businessDate)

    private companion object {
        const val ENTITY = "daily_limit_usage"
    }
}
