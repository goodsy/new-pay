package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * `merchant_registration_basis` 행(MERCHANT-FLOW-001 FLOW-3 — BR-BIZ-337 가맹점 등록 근거, **INSERT 전용**). JPA 선택은 [JpaBusinessContractEntity]와 같다(`@Immutable`·persist만·
 * `@Version` 없음·연관 매핑 없음 — runtime 권한 SELECT, INSERT만이라 UPDATE·DELETE는 1142로 이중 방어).
 */
@Entity
@Immutable
@Table(name = "merchant_registration_basis")
class JpaMerchantRegistrationBasisEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "contract_seq", nullable = false, updatable = false)
    var contractSeq: Int = 0
        protected set

    @field:Column(name = "version_no", nullable = false, updatable = false)
    var versionNo: Int = 0
        protected set

    @field:Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: Instant
        protected set

    @field:Column(name = "recorded_by", nullable = false, updatable = false, length = 64)
    lateinit var recordedBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantId == (other as JpaMerchantRegistrationBasisEntity).merchantId)

    override fun hashCode(): Int = merchantId.hashCode()

    override fun toString(): String = "JpaMerchantRegistrationBasisEntity(merchantId=$merchantId, contractSeq=$contractSeq, versionNo=$versionNo)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(merchantId: String, businessId: String, contractSeq: Int, versionNo: Int, at: Instant, by: String) = JpaMerchantRegistrationBasisEntity().also {
            it.merchantId = merchantId
            it.businessId = businessId
            it.contractSeq = contractSeq
            it.versionNo = versionNo
            it.recordedAt = at
            it.recordedBy = by
        }
    }
}
