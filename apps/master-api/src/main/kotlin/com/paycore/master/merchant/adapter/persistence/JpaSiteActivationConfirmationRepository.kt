package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.SiteActivationConfirmationRepository
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmation
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaSiteActivationConfirmationRepository(
    private val confirmations: SiteActivationConfirmationJpaRepository,
    private val revocations: ConfirmationRevocationJpaRepository,
    private val entityManager: EntityManager,
) : SiteActivationConfirmationRepository {

    override fun insert(confirmation: SiteActivationConfirmation, requestRecordId: String): Unit = translate(ENTITY) {
        require(confirmation.isValid) { "a new confirmation is not revoked" }
        entityManager.persist(
            JpaSiteActivationConfirmationEntity.newRow(
                confirmation.id.value, confirmation.merchantAccountId.value, confirmation.merchantSiteId.value, confirmation.merchantId.value,
                confirmation.paymentMethod.name, confirmation.activationId.value, confirmation.evidenceRef.value, confirmation.confirmedAt,
                confirmation.confirmedBy.value, requestRecordId, confirmation.seq,
            ),
        )
        entityManager.flush()
    }

    override fun find(id: SiteActivationConfirmationId): SiteActivationConfirmation? = translate(ENTITY) {
        entityManager.find(JpaSiteActivationConfirmationEntity::class.java, id.value)?.let {
            EntitlementPersistenceMapper.toConfirmation(it, revocations.findByConfirmations(listOf(it.confirmationId)).singleOrNull())
        }
    }

    override fun listByActivation(activationId: PaymentMethodActivationId): List<SiteActivationConfirmation> = translate(ENTITY) {
        withRevocations(confirmations.findByActivation(activationId.value))
    }

    override fun listByAccount(accountId: MerchantAccountId): List<SiteActivationConfirmation> = translate(ENTITY) {
        withRevocations(confirmations.findByAccount(accountId.value))
    }

    override fun insertRevocation(confirmation: SiteActivationConfirmation, requestRecordId: String): Unit = translate(ENTITY) {
        val r = checkNotNull(confirmation.revocation) { "the confirmation carries its revocation" }
        entityManager.persist(
            JpaConfirmationRevocationEntity.newRow(confirmation.id.value, r.reason.value, r.evidenceRef.value, r.revokedAt, r.revokedBy.value, requestRecordId),
        )
        entityManager.flush()
    }

    private fun withRevocations(rows: List<JpaSiteActivationConfirmationEntity>): List<SiteActivationConfirmation> {
        if (rows.isEmpty()) return emptyList()
        val revoked = revocations.findByConfirmations(rows.map { it.confirmationId }).associateBy { it.confirmationId }
        return rows.map { EntitlementPersistenceMapper.toConfirmation(it, revoked[it.confirmationId]) }
    }

    private companion object {
        const val ENTITY = "site_activation_confirmation"
    }
}
