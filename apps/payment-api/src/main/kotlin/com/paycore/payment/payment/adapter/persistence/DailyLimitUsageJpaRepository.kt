package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

/** AUTH-001 S2 Spring Data repository(관례 §14). 잠금 읽기는 `PessimisticRowLock.lockRow`를 쓰고 JPQL @Lock 조회는 쓰지 않는다(관례 §9). */
interface DailyLimitUsageJpaRepository : JpaRepository<JpaDailyLimitUsageEntity, LimitUsageKeyColumns> {
    /** 행이 없으면 삽입, 있으면 아무것도 바꾸지 않고 그 행의 잠금만 잡는다(최초 insert 경쟁 직렬화 — order_payment_control과 같은 관례). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO daily_limit_usage (environment, merchant_account_id, currency, business_date, created_at, updated_at, version)
            VALUES (:environment, :merchantAccountId, :currency, :businessDate, :at, :at, 0)
            ON DUPLICATE KEY UPDATE version = version
        """,
    )
    fun insertIfAbsent(
        @Param("environment") environment: String,
        @Param("merchantAccountId") merchantAccountId: String,
        @Param("currency") currency: String,
        @Param("businessDate") businessDate: LocalDate,
        @Param("at") at: Instant,
    ): Int
}
