package com.paycore.financial.paymentfact.adapter.persistence

import org.springframework.data.repository.Repository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * 좁은 Spring Data 표면(검토 m-2): `Repository`만 상속하고 아래 조회·자리 확보 메서드만 선언한다 — `save`·`delete*`·`flush` 계열이 없어 append-only 사실을 지우거나
 * merge할 경로가 이 인터페이스에 없다(쓰기는 Store의 `EntityManager.persist`·잠가 읽은 Entity 변경 감지뿐). 시험: PaymentFactRepositorySurfaceSpec.
 */
interface PaymentFactStreamJpaRepository : Repository<JpaPaymentFactStreamEntity, String> {
    /**
     * 결제별 직렬화 행 자리를 만든다(있으면 바꾸지 않고 그 행의 잠금을 기다린다 — 최초 경쟁을 PK로 직렬화, CONVENTIONS §9 IODKU). 신원은 첫 사실 값으로 고정된다.
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT INTO payment_fact_stream (payment_id, environment, merchant_account_id, merchant_site_id, merchant_order_id, fact_count, created_at, " +
            "updated_at, version) VALUES (:paymentId, :environment, :mid, :siteId, :orderId, 0, :at, :at, 0) ON DUPLICATE KEY UPDATE version = version",
    )
    @Suppress("LongParameterList")
    fun insertIfAbsent(
        @Param("paymentId") paymentId: String,
        @Param("environment") environment: String,
        @Param("mid") merchantAccountId: String,
        @Param("siteId") merchantSiteId: String,
        @Param("orderId") merchantOrderId: String,
        @Param("at") at: Instant,
    ): Int
}
