package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.EntitlementSnapshotPublisher
import com.paycore.master.merchant.application.required.CurrencyEnablementStore
import com.paycore.master.merchant.application.required.EntitlementIssueStore
import com.paycore.master.merchant.application.required.EntitlementRelayStore
import com.paycore.master.merchant.application.required.EntitlementSourceStore
import com.paycore.master.merchant.application.required.PaymentMethodActivationStore
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.RelayEnvelope
import com.paycore.master.merchant.application.required.RelayMarkResult
import com.paycore.master.merchant.application.required.SiteActivationConfirmationStore
import com.paycore.master.merchant.application.required.SiteReviewStore
import com.paycore.master.merchant.application.required.SnapshotAck
import com.paycore.master.merchant.application.required.SnapshotIssueRecord
import com.paycore.master.merchant.application.required.SnapshotPublication
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.AccountSource
import com.paycore.master.merchant.domain.ActivationStatus
import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ConfirmationRevocation
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablement
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.CurrencyUnit
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EntitlementSource
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivation
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmation
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import com.paycore.master.merchant.domain.SiteReviewDecision
import com.paycore.master.merchant.domain.SiteReviewRules
import com.paycore.master.merchant.domain.SiteReviewStatus
import com.paycore.master.merchant.domain.SnapshotPriority
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/*
 * MASTER-SNAPSHOT-001 저장 Port 구현(JPA). 공통 관례는 JpaMasterStores와 같다(MANDATORY TX, 쓰기 끝 flush, append-only는 persist만,
 * 잠금 읽기는 refresh(PESSIMISTIC_WRITE)).
 */

/** Domain ↔ 행 변환(MASTER-SNAPSHOT-001 표). 복원은 domain의 공식 복원 경로를 거친다(저장 값이 규칙상 불가능하면 예외). */
internal object EntitlementPersistenceMapper {
    fun toActivation(row: JpaPaymentMethodActivationEntity) = PaymentMethodActivation.reconstitute(
        PaymentMethodActivationId(row.activationId), MerchantAccountId(row.merchantAccountId), PaymentMethod.valueOf(row.paymentMethod),
        row.registeredAt, ActorRef(row.registeredBy), ActivationStatus.valueOf(row.status), row.evidenceRef?.let(::EvidenceRef),
        row.statusChangedAt,
    )

    fun toConfirmation(row: JpaSiteActivationConfirmationEntity, revocation: JpaConfirmationRevocationEntity?) =
        SiteActivationConfirmation.reconstitute(
            SiteActivationConfirmationId(row.confirmationId), MerchantAccountId(row.merchantAccountId), MerchantSiteId(row.merchantSiteId),
            MerchantId(row.merchantId), PaymentMethod.valueOf(row.paymentMethod), PaymentMethodActivationId(row.activationId),
            EvidenceRef(row.evidenceRef), row.confirmedAt, ActorRef(row.confirmedBy), row.confirmationSeq,
            revocation?.let { ConfirmationRevocation(DecisionReason(it.reason), EvidenceRef(it.evidenceRef), it.revokedAt, ActorRef(it.revokedBy)) },
        )

    fun toEnablement(row: JpaCurrencyEnablementEntity, unit: CurrencyUnit) = CurrencyEnablement.reconstitute(
        CurrencyEnablementId(row.enablementId), MerchantAccountId(row.merchantAccountId), unit, EffectivePeriod(row.effectiveFrom, row.effectiveTo),
        EvidenceRef(row.evidenceRef), row.createdAt, ActorRef(row.createdBy), row.endedAt, row.endedBy?.let(::ActorRef),
    )

    fun toUnit(row: JpaCurrencyUnitEntity) = CurrencyUnit(CurrencyCode(row.currencyCode), row.minorUnit)

    fun toDecision(row: JpaSiteReviewDecisionEntity) = SiteReviewDecision(
        MerchantSiteId(row.key.merchantSiteId), MerchantId(row.merchantId), row.key.seq, SiteReviewStatus.valueOf(row.decision),
        DecisionReason(row.reason), row.evidenceRef?.let(::EvidenceRef), row.decidedAt, ActorRef(row.decidedBy),
    )

    fun toIssue(row: JpaSnapshotIssueEntity) = SnapshotIssueRecord(
        MerchantId(row.key.merchantId), row.key.revision, row.key.leaseSeq, row.contentHash, row.issuedAt, row.leaseUntil,
        SnapshotPriority.valueOf(row.priority), row.outboxEventId, row.schemaVersion,
    )
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentMethodActivationStore(
    private val activations: PaymentMethodActivationJpaRepository,
    private val events: ActivationEventJpaRepository,
    private val entityManager: EntityManager,
) : PaymentMethodActivationStore {

    override fun findOpen(accountId: MerchantAccountId, method: PaymentMethod): Versioned<PaymentMethodActivation>? = translate(ENTITY) {
        activations.findOpen(accountId.value, method.name)?.let(::versioned)
    }

    override fun find(id: PaymentMethodActivationId): Versioned<PaymentMethodActivation>? = translate(ENTITY) {
        entityManager.find(JpaPaymentMethodActivationEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: PaymentMethodActivationId): Versioned<PaymentMethodActivation>? = translate(ENTITY) {
        entityManager.lockRow(JpaPaymentMethodActivationEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByAccount(accountId: MerchantAccountId): List<PaymentMethodActivation> = translate(ENTITY) {
        activations.findByAccount(accountId.value).map(EntitlementPersistenceMapper::toActivation)
    }

    override fun evidenceHistory(id: PaymentMethodActivationId): List<EvidenceRef> = translate(ENTITY) {
        events.findByActivation(id.value).mapNotNull { it.evidenceRef }.distinct().map(::EvidenceRef)
    }

    override fun insert(activation: PaymentMethodActivation, transition: ActivationTransition, requestRecordId: String): Unit = translate(ENTITY) {
        require(transition.from == null && transition.to == activation.status) { "an insert records the registration transition" }
        entityManager.persist(
            JpaPaymentMethodActivationEntity.newRow(
                activation.id.value, activation.merchantAccountId.value, activation.paymentMethod.name, activation.registeredAt,
                activation.registeredBy.value, activation.status.name, activation.evidenceRef?.value,
            ),
        )
        entityManager.flush()
        appendEvent(activation.id, transition, requestRecordId)
    }

    override fun update(activation: PaymentMethodActivation, expectedVersion: Long, transition: ActivationTransition, requestRecordId: String): Unit =
        translate(ENTITY) {
            val row = entityManager.find(JpaPaymentMethodActivationEntity::class.java, activation.id.value) ?: error("activation is not stored")
            if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
            check(row.merchantAccountId == activation.merchantAccountId.value && row.paymentMethod == activation.paymentMethod.name) {
                "an activation never changes its MID or method"
            }
            check(transition.from?.name == row.status && transition.to == activation.status) { "the transition must start from the stored status" }
            // 변경 감지: 관리되는 Entity의 필드만 바꾸면 flush 때 `UPDATE … WHERE version = ?`가 나간다(save 불필요).
            row.status = activation.status.name
            row.evidenceRef = activation.evidenceRef?.value
            row.statusChangedAt = activation.statusChangedAt
            entityManager.flush()
            appendEvent(activation.id, transition, requestRecordId)
        }

    private fun appendEvent(id: PaymentMethodActivationId, t: ActivationTransition, requestRecordId: String) {
        val seq = events.maxSeq(id.value) + 1
        entityManager.persist(
            JpaActivationEventEntity.newRow(
                ActivationEventKey.of(id.value, seq), t.from?.name, t.to.name, t.evidenceRef?.value, t.reason?.value, t.by.value, requestRecordId, t.at,
            ),
        )
        entityManager.flush()
    }

    private fun versioned(row: JpaPaymentMethodActivationEntity) =
        Versioned(EntitlementPersistenceMapper.toActivation(row), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "payment_method_activation"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaSiteActivationConfirmationStore(
    private val confirmations: SiteActivationConfirmationJpaRepository,
    private val revocations: ConfirmationRevocationJpaRepository,
    private val entityManager: EntityManager,
) : SiteActivationConfirmationStore {

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

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaCurrencyEnablementStore(
    private val units: CurrencyUnitJpaRepository,
    private val enablements: CurrencyEnablementJpaRepository,
    private val entityManager: EntityManager,
) : CurrencyEnablementStore {

    override fun findUnit(code: CurrencyCode): CurrencyUnit? = translate(ENTITY) {
        units.findById(code.value).orElse(null)?.let(EntitlementPersistenceMapper::toUnit)
    }

    override fun find(id: CurrencyEnablementId): Versioned<CurrencyEnablement>? = translate(ENTITY) {
        entityManager.find(JpaCurrencyEnablementEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: CurrencyEnablementId): Versioned<CurrencyEnablement>? = translate(ENTITY) {
        entityManager.lockRow(JpaCurrencyEnablementEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByAccount(accountId: MerchantAccountId): List<CurrencyEnablement> = translate(ENTITY) {
        enablements.findByAccount(accountId.value).map { EntitlementPersistenceMapper.toEnablement(it, unitOf(it.currencyCode)) }
    }

    override fun insert(enablement: CurrencyEnablement, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaCurrencyEnablementEntity.newRow(
                enablement.id.value, enablement.merchantAccountId.value, enablement.unit.code.value, enablement.period.from, enablement.period.to,
                enablement.evidenceRef.value, enablement.createdAt, enablement.createdBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    override fun update(enablement: CurrencyEnablement, expectedVersion: Long): Unit = translate(ENTITY) {
        val row = entityManager.find(JpaCurrencyEnablementEntity::class.java, enablement.id.value) ?: error("enablement is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.effectiveFrom == enablement.period.from && row.currencyCode == enablement.unit.code.value) { "only the end can change" }
        row.effectiveTo = enablement.period.to
        row.endedAt = enablement.endedAt
        row.endedBy = enablement.endedBy?.value
        entityManager.flush()
    }

    private fun unitOf(code: String): CurrencyUnit =
        EntitlementPersistenceMapper.toUnit(checkNotNull(entityManager.find(JpaCurrencyUnitEntity::class.java, code)) { "currency unit must exist (FK)" })

    private fun versioned(row: JpaCurrencyEnablementEntity) =
        Versioned(EntitlementPersistenceMapper.toEnablement(row, unitOf(row.currencyCode)), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "currency_enablement"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaSiteReviewStore(private val decisions: SiteReviewDecisionJpaRepository, private val entityManager: EntityManager) : SiteReviewStore {

    override fun history(siteId: MerchantSiteId): List<SiteReviewDecision> = translate(ENTITY) {
        decisions.findBySite(siteId.value).map(EntitlementPersistenceMapper::toDecision)
    }

    override fun append(decision: SiteReviewDecision, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaSiteReviewDecisionEntity.newRow(
                SiteReviewKey.of(decision.merchantSiteId.value, decision.seq), decision.merchantId.value, decision.decision.name, decision.reason.value,
                decision.evidenceRef?.value, decision.decidedAt, decision.decidedBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "merchant_site_review_decision"
    }
}

/**
 * Snapshot 조립 입력 읽기(명세 §4.6). 호출자의 MASTER TX·영속성 컨텍스트에서 읽는다 — 같은 TX에서 방금 바꾼 행(관리 Entity)이 그대로 보이고,
 * JPQL은 실행 전에 영속성 컨텍스트를 flush한다(FlushMode AUTO). jOOQ를 쓰지 않는다(JPA와 jOOQ가 저절로 같은 TX라고 가정하지 않는다, 원칙 §4).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEntitlementSourceStore(
    private val sites: MerchantSiteJpaRepository,
    private val accounts: MerchantAccountJpaRepository,
    private val assignments: SiteAssignmentJpaRepository,
    private val reviews: SiteReviewDecisionJpaRepository,
    private val contracts: JpaContractStore,
    private val activations: JpaPaymentMethodActivationStore,
    private val confirmations: JpaSiteActivationConfirmationStore,
    private val currencies: JpaCurrencyEnablementStore,
    private val limits: JpaTransactionLimitPolicyStore,
    private val policyAssignments: JpaPolicyAssignmentStore,
    private val policyVersions: JpaPolicyVersionStore,
    private val routes: JpaCardRouteActivationStore,
    private val entityManager: EntityManager,
) : EntitlementSourceStore {

    override fun load(merchantId: MerchantId): EntitlementSource? = translate(ENTITY) {
        val accountRows = accounts.findByMerchant(merchantId.value)
        if (accountRows.isEmpty()) return@translate null
        val merchant = MasterPersistenceMapper.toMerchant(
            checkNotNull(entityManager.find(JpaMerchantEntity::class.java, merchantId.value)) { "merchant must exist" },
        )
        val business = MasterPersistenceMapper.toBusiness(
            checkNotNull(entityManager.find(JpaBusinessEntity::class.java, merchant.businessId.value)) { "business must exist" },
        )
        val assignmentsByAccount = assignments.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toAssignment)
            .groupBy { it.merchantAccountId }
        val merchantLimits = limits.listByMerchant(merchantId)
        val reviewStatus = reviews.findByMerchant(merchantId.value).map(EntitlementPersistenceMapper::toDecision)
            .groupBy { it.merchantSiteId }.mapValues { (_, history) -> SiteReviewRules.currentStatus(history) }
        EntitlementSource(
            business = business,
            merchant = merchant,
            sites = sites.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toSite),
            siteReviews = reviewStatus,
            accounts = accountRows.map(MasterPersistenceMapper::toAccount).map { account ->
                val assigned = policyAssignments.listByAccount(account.id)
                AccountSource(
                    account = account,
                    issuingContract = checkNotNull(contracts.find(account.issuingBasis.contractId)) { "issuing contract must exist" },
                    assignments = assignmentsByAccount[account.id].orEmpty(),
                    activations = activations.listByAccount(account.id),
                    confirmations = confirmations.listByAccount(account.id),
                    currencies = currencies.listByAccount(account.id),
                    limitPolicies = merchantLimits,
                    policyAssignments = assigned,
                    policyVersions = policyVersions.findAll(assigned.map { it.policyVersionId }),
                    cardRoutes = routes.listByAccount(account.id),
                )
            },
        )
    }

    private companion object {
        const val ENTITY = "entitlement_source"
    }
}

/** 발행 기록 + Outbox(`ENTITLEMENT_SNAPSHOT`)를 같은 TX에 쓴다. Outbox payload = 정규 JSON 전송 payload(문자열 그대로, JSON 컬럼). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEntitlementIssueStore(private val issues: SnapshotIssueJpaRepository, private val entityManager: EntityManager) : EntitlementIssueStore {

    override fun latest(merchantId: MerchantId): SnapshotIssueRecord? = translate(ENTITY) {
        issues.findLatestFirst(merchantId.value, PageRequest.of(0, 1)).firstOrNull()?.let(EntitlementPersistenceMapper::toIssue)
    }

    override fun record(publication: SnapshotPublication): Unit = translate(ENTITY) {
        val issue = publication.issue
        require(issue.schemaVersion == publication.schemaVersion) { "the issue record and the outbox event carry the same schema version" }
        entityManager.persist(
            JpaOutboxEventEntity.pending(
                eventId = issue.eventId,
                effectKey = "${publication.eventType}:${issue.merchantId.value}:${issue.revision}:${issue.leaseSeq}",
                eventType = publication.eventType,
                schemaVersion = publication.schemaVersion,
                eventCategory = EntitlementSnapshotPublisher.EVENT_CATEGORY,
                producer = publication.producer,
                aggregateType = AGGREGATE_TYPE,
                aggregateId = issue.merchantId.value,
                aggregateVersion = issue.revision,
                occurredAt = issue.issuedAt,
                correlationId = publication.correlationId,
                causationId = publication.causationId,
                payload = publication.wirePayload,
                priority = issue.priority.value,
            ),
        )
        entityManager.flush()
        entityManager.persist(
            JpaSnapshotIssueEntity.newRow(
                SnapshotIssueKey.of(issue.merchantId.value, issue.revision, issue.leaseSeq), issue.contentHash, issue.issuedAt, issue.leaseUntil,
                issue.priority.name, issue.eventId, publication.schemaVersion,
            ),
        )
        entityManager.flush()
    }

    override fun merchantsDueForRenewal(threshold: Instant, limit: Int): List<MerchantId> = translate(ENTITY) {
        issues.merchantsWithLeaseBefore(threshold, PageRequest.of(0, limit)).map(::MerchantId)
    }

    private companion object {
        const val ENTITY = "entitlement_snapshot_issue"
        const val AGGREGATE_TYPE = "Merchant"
    }
}

/**
 * Relay 저장(명세 §4.7). claim: `SELECT id … FOR UPDATE SKIP LOCKED`(native — JPA 표준 잠금 API에 없는 조합)로 이 TX가 행을 잠근 뒤 그 행만
 * Entity로 읽어 claim을 표시한다(잠금 뒤 읽으므로 영속성 컨텍스트가 최신 값). 결과 반영은 행을 다시 잠가(`FOR UPDATE`) 상태를 확인한 뒤 바꾼다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEntitlementRelayStore(
    private val outbox: OutboxEventJpaRepository,
    private val entityManager: EntityManager,
) : EntitlementRelayStore {

    override fun claim(owner: String, now: Instant, claimUntil: Instant, limit: Int): List<RelayEnvelope> = translate(ENTITY) {
        val ids = outbox.lockClaimable(EntitlementSnapshotPublisher.EVENT_CATEGORY, now, limit)
        if (ids.isEmpty()) return@translate emptyList()
        val rows = outbox.findAllByIds(ids)
        rows.forEach {
            it.claimOwner = owner
            it.claimUntil = claimUntil
        }
        entityManager.flush()
        rows.map {
            RelayEnvelope(
                eventId = it.eventId, eventType = it.eventType, schemaVersion = it.schemaVersion, producer = it.producer,
                aggregateId = it.aggregateId, aggregateVersion = it.aggregateVersion, occurredAt = it.occurredAt, attemptCount = it.attemptCount,
                payloadJson = it.payload,
            )
        }
    }

    override fun markPublished(eventId: String, owner: String, ack: SnapshotAck): RelayMarkResult = translate(ENTITY) {
        val row = outbox.lockByEventId(eventId) ?: error("outbox event must exist")
        // ACK = PAYMENT가 내구 커밋했다는 사실이다. claim이 다른 인스턴스로 넘어갔어도 전달은 끝났으므로 PUBLISHED로 둔다(다른 쪽은 DUPLICATE를 받는다).
        entityManager.persist(JpaSnapshotAckEntity.newRow(eventId, ack.consumer, ack.outcome, ack.appliedRevision, ack.ackedAt, ack.originalOutcome))
        val result = when (row.publishStatus) {
            JpaOutboxEventEntity.PUBLISHED -> RelayMarkResult.ALREADY_PUBLISHED
            JpaOutboxEventEntity.PENDING -> {
                row.publishStatus = JpaOutboxEventEntity.PUBLISHED
                row.publishedAt = ack.ackedAt
                row.claimOwner = null
                row.claimUntil = null
                RelayMarkResult.MARKED
            }
            else -> RelayMarkResult.CLAIM_LOST
        }
        entityManager.flush()
        result
    }

    override fun markRetry(eventId: String, owner: String, nextAttemptAt: Instant, failureCode: String): RelayMarkResult = translate(ENTITY) {
        markClaimed(eventId, owner) { row ->
            row.attemptCount += 1
            row.nextAttemptAt = nextAttemptAt
            row.lastFailureCode = failureCode.take(FAILURE_CODE_LENGTH)
        }
    }

    override fun markQuarantined(eventId: String, owner: String, failureCode: String): RelayMarkResult = translate(ENTITY) {
        markClaimed(eventId, owner) { row ->
            row.publishStatus = JpaOutboxEventEntity.QUARANTINED
            row.attemptCount += 1
            row.lastFailureCode = failureCode.take(FAILURE_CODE_LENGTH)
        }
    }

    /** 이 소유자의 claim이 유지된 PENDING 행만 바꾼다(claim이 넘어갔으면 덮어쓰지 않는다). claim은 해제한다. */
    private fun markClaimed(eventId: String, owner: String, change: (JpaOutboxEventEntity) -> Unit): RelayMarkResult {
        val row = outbox.lockByEventId(eventId) ?: error("outbox event must exist")
        if (row.publishStatus == JpaOutboxEventEntity.PUBLISHED) return RelayMarkResult.ALREADY_PUBLISHED
        if (row.publishStatus != JpaOutboxEventEntity.PENDING || row.claimOwner != owner) return RelayMarkResult.CLAIM_LOST
        change(row)
        row.claimOwner = null
        row.claimUntil = null
        entityManager.flush()
        return RelayMarkResult.MARKED
    }

    private companion object {
        const val ENTITY = "outbox_event"
        const val FAILURE_CODE_LENGTH = 48
    }
}
