package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.CardRouteActivationStore
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.PolicyAssignmentStore
import com.paycore.master.merchant.application.required.PolicyVersionStore
import com.paycore.master.merchant.application.required.TransactionLimitPolicyStore
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.ActivationStatus
import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.CardRouteActivation
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitScope
import com.paycore.master.merchant.domain.LimitScopeKind
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PolicyAssignment
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersion
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.RouteRef
import com.paycore.master.merchant.domain.TransactionLimitPolicy
import com.paycore.master.merchant.domain.TransactionLimitPolicyId
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/*
 * MASTER-SNAPSHOT-002 저장 Port 구현(JPA). 공통 관례는 JpaMasterStores와 같다(MANDATORY TX, 쓰기 끝 flush, append-only는 persist만,
 * 잠금 읽기는 lockRow = refresh(PESSIMISTIC_WRITE)). 이 표들은 jOOQ로 쓰지 않는다(Command = JPA, 원칙 §4).
 */

interface TransactionLimitPolicyJpaRepository : JpaRepository<JpaTransactionLimitPolicyEntity, String> {
    @Query("select p from JpaTransactionLimitPolicyEntity p where p.merchantId = :merchantId order by p.effectiveFrom, p.policyId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaTransactionLimitPolicyEntity>
}

interface PolicyVersionJpaRepository : JpaRepository<JpaPolicyVersionEntity, String> {
    @Query("select v from JpaPolicyVersionEntity v where v.policyKind = :kind and v.policyCode = :code order by v.versionNo")
    fun findByCode(@Param("kind") kind: String, @Param("code") code: String): List<JpaPolicyVersionEntity>

    @Query("select v from JpaPolicyVersionEntity v where v.policyVersionId in :ids")
    fun findByIds(@Param("ids") ids: Collection<String>): List<JpaPolicyVersionEntity>
}

interface PolicyAssignmentJpaRepository : JpaRepository<JpaPolicyAssignmentEntity, String> {
    @Query("select a from JpaPolicyAssignmentEntity a where a.merchantAccountId = :accountId order by a.effectiveFrom, a.assignmentId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaPolicyAssignmentEntity>
}

interface CardRouteActivationJpaRepository : JpaRepository<JpaCardRouteActivationEntity, String> {
    @Query("select r from JpaCardRouteActivationEntity r where r.merchantAccountId = :accountId order by r.registeredAt, r.activationId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaCardRouteActivationEntity>
}

interface CardRouteEventJpaRepository : JpaRepository<JpaCardRouteEventEntity, CardRouteEventKey> {
    @Query("select coalesce(max(e.key.seq), 0) from JpaCardRouteEventEntity e where e.key.activationId = :activationId")
    fun maxSeq(@Param("activationId") activationId: String): Int

    @Query("select e from JpaCardRouteEventEntity e where e.key.activationId = :activationId order by e.key.seq")
    fun findByActivation(@Param("activationId") activationId: String): List<JpaCardRouteEventEntity>
}

/** Domain ↔ 행 변환. 복원은 domain의 공식 복원 경로를 거친다(저장 값이 규칙상 불가능하면 예외 — Seoul 자정 등). */
internal object AuthorizationTermsMapper {
    fun toLimit(row: JpaTransactionLimitPolicyEntity): TransactionLimitPolicy {
        val scope = when (LimitScopeKind.valueOf(row.scopeKind)) {
            LimitScopeKind.CSA_DEFAULT -> LimitScope.CsaDefault(ContractServiceAgreementId(checkNotNull(row.agreementId)))
            LimitScopeKind.MID_OVERRIDE -> LimitScope.MidOverride(MerchantAccountId(checkNotNull(row.merchantAccountId)))
        }
        return TransactionLimitPolicy.reconstitute(
            TransactionLimitPolicyId(row.policyId), MerchantId(row.merchantId), scope, CurrencyCode(row.currencyCode),
            limitOf(row.perTxKind, row.perTxAmount), limitOf(row.dailyKind, row.dailyAmount), EffectivePeriod(row.effectiveFrom, row.effectiveTo),
            DecisionReason(row.reason), row.createdAt, ActorRef(row.createdBy), row.endedAt, row.endedBy?.let(::ActorRef),
        )
    }

    fun limitColumns(v: LimitValue): Pair<String, Long?> = when (v) {
        LimitValue.Unlimited -> "UNLIMITED" to null
        is LimitValue.Limited -> "LIMITED" to v.amountMinor
    }

    private fun limitOf(kind: String, amount: Long?): LimitValue = when (kind) {
        "UNLIMITED" -> LimitValue.Unlimited.also { check(amount == null) { "an unlimited value has no amount" } }
        "LIMITED" -> LimitValue.Limited(checkNotNull(amount) { "a limited value has an amount" })
        else -> error("unknown limit kind")
    }

    fun toVersion(row: JpaPolicyVersionEntity) = PolicyVersion.reconstitute(
        PolicyVersionId(row.policyVersionId), PolicyKind.valueOf(row.policyKind), PolicyCode(row.policyCode), row.versionNo,
        PolicyContentStatus.valueOf(row.contentStatus), row.contentHash, DecisionReason(row.reason), row.registeredAt, ActorRef(row.registeredBy),
    )

    fun toAssignment(row: JpaPolicyAssignmentEntity) = PolicyAssignment.reconstitute(
        PolicyAssignmentId(row.assignmentId), MerchantId(row.merchantId), ContractServiceAgreementId(row.agreementId), MerchantAccountId(row.merchantAccountId),
        CurrencyCode(row.currencyCode), PolicyKind.valueOf(row.policyKind), PolicyVersionId(row.policyVersionId),
        EffectivePeriod(row.effectiveFrom, row.effectiveTo), DecisionReason(row.reason), row.createdAt, ActorRef(row.createdBy), row.endedAt,
        row.endedBy?.let(::ActorRef),
    )

    fun toRoute(row: JpaCardRouteActivationEntity) = CardRouteActivation.reconstitute(
        CardRouteActivationId(row.activationId), MerchantAccountId(row.merchantAccountId), PaymentMethod.valueOf(row.paymentMethod),
        AcquirerCode(row.acquirerCode), RouteRef(row.routeRef), InstitutionMerchantRef(row.institutionMerchantRef), row.registeredAt,
        ActorRef(row.registeredBy), ActivationStatus.valueOf(row.status), row.evidenceRef?.let(::EvidenceRef), row.statusChangedAt,
    )
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaTransactionLimitPolicyStore(
    private val policies: TransactionLimitPolicyJpaRepository,
    private val agreements: ContractServiceAgreementJpaRepository,
    private val entityManager: EntityManager,
) : TransactionLimitPolicyStore {

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

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPolicyVersionStore(private val versions: PolicyVersionJpaRepository, private val entityManager: EntityManager) : PolicyVersionStore {

    override fun find(id: PolicyVersionId): PolicyVersion? = translate(ENTITY) {
        entityManager.find(JpaPolicyVersionEntity::class.java, id.value)?.let(AuthorizationTermsMapper::toVersion)
    }

    override fun findAll(ids: Collection<PolicyVersionId>): List<PolicyVersion> = translate(ENTITY) {
        if (ids.isEmpty()) emptyList() else versions.findByIds(ids.map { it.value }.toSet()).map(AuthorizationTermsMapper::toVersion)
    }

    override fun listByCode(kind: PolicyKind, code: PolicyCode): List<PolicyVersion> = translate(ENTITY) {
        versions.findByCode(kind.name, code.value).map(AuthorizationTermsMapper::toVersion)
    }

    override fun insert(version: PolicyVersion, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaPolicyVersionEntity.newRow(
                version.id.value, version.kind.name, version.code.value, version.versionNo, version.contentStatus.name, version.contentHash,
                version.reason.value, version.registeredAt, version.registeredBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "policy_version"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPolicyAssignmentStore(private val assignments: PolicyAssignmentJpaRepository, private val entityManager: EntityManager) : PolicyAssignmentStore {

    override fun find(id: PolicyAssignmentId): Versioned<PolicyAssignment>? = translate(ENTITY) {
        entityManager.find(JpaPolicyAssignmentEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: PolicyAssignmentId): Versioned<PolicyAssignment>? = translate(ENTITY) {
        entityManager.lockRow(JpaPolicyAssignmentEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByAccount(accountId: MerchantAccountId): List<PolicyAssignment> = translate(ENTITY) {
        assignments.findByAccount(accountId.value).map(AuthorizationTermsMapper::toAssignment)
    }

    override fun insert(assignment: PolicyAssignment, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaPolicyAssignmentEntity.newRow(
                assignment.id.value, assignment.merchantId.value, assignment.agreementId.value, assignment.merchantAccountId.value,
                assignment.currency.value, assignment.kind.name, assignment.policyVersionId.value, assignment.period.from, assignment.period.to,
                assignment.reason.value, assignment.createdAt, assignment.createdBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    override fun update(assignment: PolicyAssignment, expectedVersion: Long): Unit = translate(ENTITY) {
        val row = entityManager.find(JpaPolicyAssignmentEntity::class.java, assignment.id.value) ?: error("assignment is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.effectiveFrom == assignment.period.from && row.policyVersionId == assignment.policyVersionId.value) { "only the end can change" }
        row.effectiveTo = assignment.period.to
        row.endedAt = assignment.endedAt
        row.endedBy = assignment.endedBy?.value
        entityManager.flush()
    }

    private fun versioned(row: JpaPolicyAssignmentEntity) = Versioned(AuthorizationTermsMapper.toAssignment(row), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "policy_assignment"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaCardRouteActivationStore(
    private val routes: CardRouteActivationJpaRepository,
    private val events: CardRouteEventJpaRepository,
    private val accounts: MerchantAccountJpaRepository,
    private val entityManager: EntityManager,
) : CardRouteActivationStore {

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
