package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.CardRouteActivationRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.CardRouteActivation
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaCardRouteActivationRepository(
    private val routes: CardRouteActivationJpaRepository,
    private val events: CardRouteEventJpaRepository,
    private val accounts: MerchantAccountJpaRepository,
    private val entityManager: EntityManager,
) : CardRouteActivationRepository {

    override fun find(id: CardRouteActivationId): Versioned<CardRouteActivation>? = translate(ENTITY) {
        entityManager.find(JpaCardRouteActivationEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: CardRouteActivationId): Versioned<CardRouteActivation>? = translate(ENTITY) {
        entityManager.lockRow(JpaCardRouteActivationEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByAccount(accountId: MerchantAccountId): List<CardRouteActivation> = translate(ENTITY) {
        routes.findByAccount(accountId.value).map(AuthorizationTermsMapper::toRoute)
    }

    override fun evidenceHistory(id: CardRouteActivationId): List<EvidenceRef> = translate(ENTITY) {
        events.findByActivation(id.value).mapNotNull { it.evidenceRef }.distinct().map(::EvidenceRef)
    }

    override fun insert(route: CardRouteActivation, transition: ActivationTransition, requestRecordId: String): Unit = translate(ENTITY) {
        require(transition.from == null && transition.to == route.status) { "an insert records the registration transition" }
        val merchantId = checkNotNull(accounts.findById(route.merchantAccountId.value).orElse(null)) { "MID must exist (FK)" }.merchantId
        entityManager.persist(
            JpaCardRouteActivationEntity.newRow(
                route.id.value, route.merchantAccountId.value, merchantId, route.paymentMethod.name, route.acquirerCode.value, route.routeRef.value,
                route.institutionMerchantRef.value, route.status.name, route.registeredAt, route.registeredBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
        appendEvent(route.id, transition, requestRecordId)
    }

    override fun update(route: CardRouteActivation, expectedVersion: Long, transition: ActivationTransition, requestRecordId: String): Unit =
        translate(ENTITY) {
            val row = entityManager.find(JpaCardRouteActivationEntity::class.java, route.id.value) ?: error("card route is not stored")
            if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
            check(row.merchantAccountId == route.merchantAccountId.value && row.acquirerCode == route.acquirerCode.value) {
                "a card route never changes its MID or acquirer"
            }
            check(transition.from?.name == row.status && transition.to == route.status) { "the transition must start from the stored status" }
            row.status = route.status.name
            row.evidenceRef = route.evidenceRef?.value
            row.statusChangedAt = route.statusChangedAt
            entityManager.flush()
            appendEvent(route.id, transition, requestRecordId)
        }

    private fun appendEvent(id: CardRouteActivationId, t: ActivationTransition, requestRecordId: String) {
        val seq = events.maxSeq(id.value) + 1
        entityManager.persist(
            JpaCardRouteEventEntity.newRow(
                CardRouteEventKey.of(id.value, seq), t.from?.name, t.to.name, t.evidenceRef?.value, t.reason?.value, t.by.value, requestRecordId, t.at,
            ),
        )
        entityManager.flush()
    }

    private fun versioned(row: JpaCardRouteActivationEntity) = Versioned(AuthorizationTermsMapper.toRoute(row), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "card_route_activation"
    }
}
