package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant

/**
 * `merchant_code_assignment` 행(MERCHANT-CODE-001 §3.2·§3.7.4 — append-only 배정 이력).
 *
 * JPA 선택(이 코드의 역할·이유·주의점):
 * - `@Immutable` + 모든 열 `updatable = false` + protected setter(Kotlin JPA 클래스는 open이라 private setter 불가 — 호출자 0은 ArchUnit MC-1-7): Hibernate가 이 Entity에 UPDATE를 만들 길이 없다. 한 번 배정된 코드는 영구 점유이고(runtime 권한도 SELECT, INSERT만),
 *   변경은 새 행(seq 2) INSERT로만 표현한다.
 * - `JpaMerchantEntity`에 `@OneToMany`를 두지 않는다: merchant 갱신(entitlement revision +1)이 자식 컬렉션을 다시 저장하거나 지연 로딩이 판정 입력을 오래된 값으로 만드는 위험을
 *   피하려고 [JpaMerchantRepository]가 따로 읽어 Mapper로 조립한다(`JpaContractRepository`의 Revision·승인 행과 같은 방식).
 * - 복합 PK(merchant_id, assignment_seq)는 `@IdClass`. 등록 TX는 persist 직후 flush해 UNIQUE 1062가 Port 안에서 `StoreConstraint.MERCHANT_CODE`로 번역되게 한다.
 */
@Entity
@Immutable
@IdClass(JpaMerchantCodeAssignmentEntity.Key::class)
@Table(name = "merchant_code_assignment")
class JpaMerchantCodeAssignmentEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Id
    // V13 TINYINT(seq 1·2) — Kotlin Int로 쓰고 JDBC 형식만 TINYINT로 맞춘다(schema validate).
    @field:JdbcTypeCode(SqlTypes.TINYINT)
    @field:Column(name = "assignment_seq", nullable = false, updatable = false)
    var assignmentSeq: Int = 0
        protected set

    @field:Column(name = "merchant_code", nullable = false, updatable = false, length = 14)
    lateinit var merchantCode: String
        protected set

    @field:Column(name = "replaces_code", updatable = false, length = 14)
    var replacesCode: String? = null
        protected set

    @field:Column(name = "source", nullable = false, updatable = false, length = 16)
    lateinit var source: String
        protected set

    @field:Column(name = "approval_id", updatable = false, length = 30)
    var approvalId: String? = null
        protected set

    @field:Column(name = "reason_code", updatable = false, length = 32)
    var reasonCode: String? = null
        protected set

    @field:Column(name = "assigned_at", nullable = false, updatable = false)
    lateinit var assignedAt: Instant
        protected set

    @field:Column(name = "assigned_by", nullable = false, updatable = false, length = 64)
    lateinit var assignedBy: String
        protected set

    /** 복합 PK 값(@IdClass). */
    data class Key(var merchantId: String = "", var assignmentSeq: Int = 0) : Serializable

    override fun equals(other: Any?): Boolean =
        this === other || (
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantId == (other as JpaMerchantCodeAssignmentEntity).merchantId &&
                assignmentSeq == other.assignmentSeq
            )

    override fun hashCode(): Int = 31 * merchantId.hashCode() + assignmentSeq

    override fun toString(): String = "JpaMerchantCodeAssignmentEntity(merchantId=$merchantId, assignmentSeq=$assignmentSeq, source=$source)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            merchantId: String, seq: Int, code: String, replacesCode: String?, source: String, approvalId: String?, reasonCode: String?, at: Instant, by: String,
        ) = JpaMerchantCodeAssignmentEntity().also {
            it.merchantId = merchantId
            it.assignmentSeq = seq
            it.merchantCode = code
            it.replacesCode = replacesCode
            it.source = source
            it.approvalId = approvalId
            it.reasonCode = reasonCode
            it.assignedAt = at
            it.assignedBy = by
        }
    }
}
