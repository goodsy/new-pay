package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.TransactionLimitPolicyRepository
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.LimitScope
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.TransactionLimitPolicy
import com.paycore.master.merchant.domain.TransactionLimitPolicyId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/*
 * MASTER-SNAPSHOT-002 저장 Port 구현(JPA). 공통 관례는 `PessimisticRowLock.kt` 머리말과 같다(MANDATORY TX, 쓰기 끝 flush, append-only는 persist만,
 * 잠금 읽기는 lockRow = refresh(PESSIMISTIC_WRITE)). 이 표들은 jOOQ로 쓰지 않는다(Command = JPA, 원칙 §4).
 */

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaTransactionLimitPolicyRepository(
    private val policies: TransactionLimitPolicyJpaRepository,
    private val agreements: ContractServiceAgreementJpaRepository,
    private val entityManager: EntityManager,
) : TransactionLimitPolicyRepository {

    override fun find(id: TransactionLimitPolicyId): Versioned<TransactionLimitPolicy>? = translate(ENTITY) {
        entityManager.find(JpaTransactionLimitPolicyEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: TransactionLimitPolicyId): Versioned<TransactionLimitPolicy>? = translate(ENTITY) {
        entityManager.lockRow(JpaTransactionLimitPolicyEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByMerchant(merchantId: MerchantId): List<TransactionLimitPolicy> = translate(ENTITY) {
        policies.findByMerchant(merchantId.value).map(AuthorizationTermsMapper::toLimit)
    }

    override fun insert(policy: TransactionLimitPolicy, requestRecordId: String): Unit = translate(ENTITY) {
        val (contractId, agreementId, accountId) = when (val scope = policy.scope) {
            is LimitScope.CsaDefault -> Triple(
                checkNotNull(contractOfAgreement(scope.agreementId)) { "agreement must exist (FK)" }.value, scope.agreementId.value, null,
            )
            is LimitScope.MidOverride -> Triple(null, null, scope.merchantAccountId.value)
        }
        entityManager.persist(
            JpaTransactionLimitPolicyEntity.newRow(
                policy.id.value, policy.merchantId.value, policy.scope.kind.name, contractId, agreementId, accountId, policy.currency.value,
                AuthorizationTermsMapper.limitColumns(policy.perTransaction), AuthorizationTermsMapper.limitColumns(policy.daily), policy.period.from,
                policy.period.to, policy.reason.value, policy.createdAt, policy.createdBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    override fun update(policy: TransactionLimitPolicy, expectedVersion: Long): Unit = translate(ENTITY) {
        val row = entityManager.find(JpaTransactionLimitPolicyEntity::class.java, policy.id.value) ?: error("limit policy is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.effectiveFrom == policy.period.from && row.currencyCode == policy.currency.value) { "only the end can change" }
        // 변경 감지: 관리되는 Entity의 종료 필드만 바꾸면 flush 때 `UPDATE … WHERE version = ?`.
        row.effectiveTo = policy.period.to
        row.endedAt = policy.endedAt
        row.endedBy = policy.endedBy?.value
        entityManager.flush()
    }

    override fun contractOfAgreement(agreementId: ContractServiceAgreementId): ContractId? = translate(ENTITY) {
        agreements.findById(agreementId.value).orElse(null)?.let { ContractId(it.contractId) }
    }

    private fun versioned(row: JpaTransactionLimitPolicyEntity) = Versioned(AuthorizationTermsMapper.toLimit(row), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "transaction_limit_policy"
    }
}
