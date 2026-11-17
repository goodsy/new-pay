package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * idempotency_request 행. 최초 INSERT는 adapter의 native `INSERT … ON DUPLICATE KEY`(같은 키 동시 요청 직렬화)이고, JPA는 잠가 읽고
 * 결과·갱신을 반영한다(변경 감지 + `@Version`). 범위 컬럼(environment, MID, operation, key)은 바뀌지 않는다.
 */
@Entity
@Table(name = "idempotency_request")
class JpaIdempotencyRequestEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long = 0
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 40)
    lateinit var operation: String
        protected set

    @field:Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    lateinit var idempotencyKey: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, length = 64)
    lateinit var requestRecordId: String

    @field:Column(name = "request_fingerprint", nullable = false, length = 80)
    lateinit var requestFingerprint: String

    @field:Column(name = "result_kind", nullable = false, length = 16)
    lateinit var resultKind: String

    @field:Column(name = "result_code", length = 48)
    var resultCode: String? = null

    @field:Column(name = "result_payment_id", length = 64)
    var resultPaymentId: String? = null

    /** AUTH-001 V11: confirm 성공의 Authorization 참조(재생 = 그 승인의 현재 상태, D-AUTH-004). CreatePayment 행은 NULL. */
    @field:Column(name = "result_authorization_id", length = 64)
    var resultAuthorizationId: String? = null

    /** CANCEL-001 V20: 취소 성공의 취소 참조(재생 = 그 취소의 현재 상태, CC-05). 다른 operation 행은 NULL. */
    @field:Column(name = "result_cancellation_id", length = 64)
    var resultCancellationId: String? = null

    /** AUTH-001 V11: confirm 거절의 고정 분류 상세(예: PAYMENT_NOT_ELIGIBLE reason). */
    @field:Column(name = "result_detail", length = 48)
    var resultDetail: String? = null

    /**
     * MERCHANT-CODE-001 C-37(MC-4) V36: CreatePayment 성공 결과를 만든 요청 때 읽은 가맹점 코드(재생 = 이 저장값 — D-CP-003). 코드 없는 MID·다른 operation·MC-4 이전 행은 NULL.
     * 일반 열(결과는 PENDING → 결과 한 번만 쓰이고, 만료 갱신 때만 NULL로 되돌아간다 — 다른 result_* 열과 같은 수명).
     */
    @field:Column(name = "result_merchant_code", length = 14)
    var resultMerchantCode: String? = null

    @field:Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @field:Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id == (other as JpaIdempotencyRequestEntity).id
    }

    override fun hashCode(): Int = id.hashCode()

    /** 멱등키 값은 넣지 않는다(가맹점 입력, 로그 노출 최소화). */
    override fun toString(): String = "JpaIdempotencyRequestEntity(id=$id, resultKind=$resultKind, version=$version)"
}
