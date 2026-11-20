package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * `contract_code` 행(SITE-CONTRACT-CODE-001 §3.3·§7 — 계약 사람용 코드, append-only·불변).
 *
 * JPA 선택(이 코드의 역할·예·이유·주의점):
 * - 역할: Domain 값 객체 `ContractCode`(순수 Kotlin)를 저장하는 adapter 쪽 표현이다. Domain `Contract`는 이 Entity도 코드도 모른다(C-12 — 코드는 Aggregate 필드가 아니다).
 *   예: `registerContract`가 `contracts.insertNew(contract)`(계약 행 persist·flush) 다음 `contracts.insertCode(…)`로 이 행을 같은 TX에 persist한다.
 * - `@Immutable` + 모든 열 `updatable = false` + protected setter(Kotlin JPA 클래스는 open이라 private setter 불가 — 호출자 0은 ArchUnit): Hibernate가 이 행에 UPDATE를 만들 길이 없다
 *   (runtime 권한도 SELECT, INSERT만 — 불변·재사용 금지 Q-SC-5).
 * - 주의 ①: `JpaContractEntity`에 연관(`@OneToOne`·`@OneToMany`)으로 묶지 않고 독립 persist한다 — cascade·dirty checking이 불변 행에 UPDATE를 만들 여지를 없앤다.
 * - 주의 ④: Hibernate `order_inserts`를 설정하지 않았으므로 INSERT 순서 = persist 순서다. 계약 행이 먼저 flush돼 있어야 FK(`fk_cc_contract`)가 만족된다(역순이면 1452).
 * - `code_seq` SMALLINT — Kotlin Int로 쓰고 JDBC 형식만 SMALLINT로 맞춘다(schema validate).
 */
@Entity
@Immutable
@Table(name = "contract_code")
class JpaContractCodeEntity protected constructor() {
    @field:Id
    @field:Column(name = "contract_id", nullable = false, updatable = false, length = 64)
    lateinit var contractId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:JdbcTypeCode(SqlTypes.SMALLINT)
    @field:Column(name = "code_seq", nullable = false, updatable = false)
    var codeSeq: Int = 0
        protected set

    @field:Column(name = "issued_merchant_code", nullable = false, updatable = false, length = 14)
    lateinit var issuedMerchantCode: String
        protected set

    @field:Column(name = "contract_code", nullable = false, updatable = false, length = 32)
    lateinit var contractCode: String
        protected set

    @field:Column(name = "source", nullable = false, updatable = false, length = 16)
    lateinit var source: String
        protected set

    @field:Column(name = "assigned_at", nullable = false, updatable = false)
    lateinit var assignedAt: Instant
        protected set

    @field:Column(name = "assigned_by", nullable = false, updatable = false, length = 64)
    lateinit var assignedBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && contractId == (other as JpaContractCodeEntity).contractId)

    override fun hashCode(): Int = contractId.hashCode()

    override fun toString(): String = "JpaContractCodeEntity(contractId=$contractId, codeSeq=$codeSeq, source=$source)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(contractId: String, merchantId: String, seq: Int, issuedMerchantCode: String, code: String, source: String, at: Instant, by: String) =
            JpaContractCodeEntity().also {
                it.contractId = contractId
                it.merchantId = merchantId
                it.codeSeq = seq
                it.issuedMerchantCode = issuedMerchantCode
                it.contractCode = code
                it.source = source
                it.assignedAt = at
                it.assignedBy = by
            }
    }
}
