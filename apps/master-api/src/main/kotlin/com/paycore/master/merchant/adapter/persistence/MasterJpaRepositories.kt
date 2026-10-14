package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/*
 * Spring Data repository(adapter.persistence 전용). application은 이것을 모르고 application.required Port 구현(Jpa*Store)만 안다.
 * 잠금 읽기는 Store가 EntityManager.refresh(PESSIMISTIC_WRITE)로 한다(이미 영속성 컨텍스트에 있는 Entity도 최신 커밋 값으로 다시 읽기 위해).
 */

interface BusinessJpaRepository : JpaRepository<JpaBusinessEntity, String> {
    @Query("select b.businessId from JpaBusinessEntity b where b.registrationNumber = :registrationNumber")
    fun findIdByRegistrationNumber(@Param("registrationNumber") registrationNumber: String): String?
}

interface MerchantJpaRepository : JpaRepository<JpaMerchantEntity, String>

interface MerchantSiteJpaRepository : JpaRepository<JpaMerchantSiteEntity, String> {
    @Query(
        "select s.merchantSiteId from JpaMerchantSiteEntity s where s.channelType = :channelType " +
            "and s.channelIdentifier = :channelIdentifier and s.lifecycle = 'ACTIVE'",
    )
    fun findActiveByChannel(@Param("channelType") channelType: String, @Param("channelIdentifier") channelIdentifier: String): String?

    @Query("select s from JpaMerchantSiteEntity s where s.merchantId = :merchantId order by s.merchantSiteId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaMerchantSiteEntity>
}

interface ContractJpaRepository : JpaRepository<JpaContractEntity, String>

interface ContractRevisionJpaRepository : JpaRepository<JpaContractRevisionEntity, RevisionKey> {
    @Query("select r from JpaContractRevisionEntity r where r.key.contractId = :contractId order by r.key.revisionNo")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractRevisionEntity>
}

interface ContractRevisionScopeSiteJpaRepository : JpaRepository<JpaContractRevisionScopeSiteEntity, ScopeSiteKey> {
    @Query("select s from JpaContractRevisionScopeSiteEntity s where s.key.contractId = :contractId")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractRevisionScopeSiteEntity>
}

interface ContractRevisionApprovalJpaRepository : JpaRepository<JpaContractRevisionApprovalEntity, RevisionKey> {
    @Query("select a from JpaContractRevisionApprovalEntity a where a.key.contractId = :contractId")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractRevisionApprovalEntity>
}

interface ContractServiceAgreementJpaRepository : JpaRepository<JpaContractServiceAgreementEntity, String> {
    @Query("select a from JpaContractServiceAgreementEntity a where a.contractId = :contractId order by a.addedAt, a.agreementId")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractServiceAgreementEntity>
}

interface MerchantAccountJpaRepository : JpaRepository<JpaMerchantAccountEntity, String> {
    @Query("select count(a) from JpaMerchantAccountEntity a where a.merchantId = :merchantId")
    fun countByMerchant(@Param("merchantId") merchantId: String): Long

    @Query("select a from JpaMerchantAccountEntity a where a.merchantId = :merchantId order by a.merchantAccountId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaMerchantAccountEntity>
}

interface DefaultDesignationJpaRepository : JpaRepository<JpaDefaultDesignationEntity, String>

interface SiteAssignmentJpaRepository : JpaRepository<JpaSiteAssignmentEntity, String> {
    @Query("select a from JpaSiteAssignmentEntity a where a.merchantAccountId = :accountId order by a.effectiveFrom, a.assignmentId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaSiteAssignmentEntity>

    @Query("select a from JpaSiteAssignmentEntity a where a.merchantId = :merchantId order by a.effectiveFrom, a.assignmentId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaSiteAssignmentEntity>
}

interface PaymentMethodActivationJpaRepository : JpaRepository<JpaPaymentMethodActivationEntity, String> {
    @Query(
        "select a from JpaPaymentMethodActivationEntity a where a.merchantAccountId = :accountId and a.paymentMethod = :method " +
            "and a.status <> 'TERMINATED'",
    )
    fun findOpen(@Param("accountId") accountId: String, @Param("method") method: String): JpaPaymentMethodActivationEntity?

    @Query("select a from JpaPaymentMethodActivationEntity a where a.merchantAccountId = :accountId order by a.registeredAt, a.activationId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaPaymentMethodActivationEntity>
}

interface ActivationEventJpaRepository : JpaRepository<JpaActivationEventEntity, ActivationEventKey> {
    @Query("select coalesce(max(e.key.seq), 0) from JpaActivationEventEntity e where e.key.activationId = :activationId")
    fun maxSeq(@Param("activationId") activationId: String): Int

    @Query("select e from JpaActivationEventEntity e where e.key.activationId = :activationId order by e.key.seq")
    fun findByActivation(@Param("activationId") activationId: String): List<JpaActivationEventEntity>
}

interface SiteActivationConfirmationJpaRepository : JpaRepository<JpaSiteActivationConfirmationEntity, String> {
    @Query("select c from JpaSiteActivationConfirmationEntity c where c.activationId = :activationId order by c.confirmedAt, c.confirmationId")
    fun findByActivation(@Param("activationId") activationId: String): List<JpaSiteActivationConfirmationEntity>

    @Query("select c from JpaSiteActivationConfirmationEntity c where c.merchantAccountId = :accountId order by c.confirmedAt, c.confirmationId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaSiteActivationConfirmationEntity>
}

interface ConfirmationRevocationJpaRepository : JpaRepository<JpaConfirmationRevocationEntity, String> {
    @Query("select r from JpaConfirmationRevocationEntity r where r.confirmationId in :ids")
    fun findByConfirmations(@Param("ids") ids: Collection<String>): List<JpaConfirmationRevocationEntity>
}

interface CurrencyUnitJpaRepository : JpaRepository<JpaCurrencyUnitEntity, String>

interface CurrencyEnablementJpaRepository : JpaRepository<JpaCurrencyEnablementEntity, String> {
    @Query("select e from JpaCurrencyEnablementEntity e where e.merchantAccountId = :accountId order by e.currencyCode, e.effectiveFrom")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaCurrencyEnablementEntity>
}

interface SiteReviewDecisionJpaRepository : JpaRepository<JpaSiteReviewDecisionEntity, SiteReviewKey> {
    @Query("select d from JpaSiteReviewDecisionEntity d where d.key.merchantSiteId = :siteId order by d.key.seq")
    fun findBySite(@Param("siteId") siteId: String): List<JpaSiteReviewDecisionEntity>

    @Query("select d from JpaSiteReviewDecisionEntity d where d.merchantId = :merchantId order by d.key.merchantSiteId, d.key.seq")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaSiteReviewDecisionEntity>
}

interface SnapshotIssueJpaRepository : JpaRepository<JpaSnapshotIssueEntity, SnapshotIssueKey> {
    @Query("select i from JpaSnapshotIssueEntity i where i.key.merchantId = :merchantId order by i.key.revision desc, i.key.leaseSeq desc")
    fun findLatestFirst(@Param("merchantId") merchantId: String, page: org.springframework.data.domain.Pageable): List<JpaSnapshotIssueEntity>

    @Query(
        "select i.key.merchantId from JpaSnapshotIssueEntity i group by i.key.merchantId having max(i.leaseUntil) < :threshold " +
            "order by max(i.leaseUntil)",
    )
    fun merchantsWithLeaseBefore(@Param("threshold") threshold: Instant, page: org.springframework.data.domain.Pageable): List<String>
}

interface SnapshotAckJpaRepository : JpaRepository<JpaSnapshotAckEntity, Long>

interface OutboxEventJpaRepository : JpaRepository<JpaOutboxEventEntity, Long> {
    /**
     * Relay claim 후보(명세 §4.7): ENTITLEMENT_SNAPSHOT·PENDING·재시도 시각 도래·claim 없음/만료, 우선순위 높은 것부터 id 순. 다른 Relay가 잠근
     * 행은 건너뛴다(`FOR UPDATE SKIP LOCKED` — JPA 표준 잠금 API에 없는 조합이라 native). 같은 TX에서 claim을 표시한다.
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT id FROM outbox_event
            WHERE event_category = :category AND publish_status = 'PENDING' AND next_attempt_at <= :now
              AND (claim_until IS NULL OR claim_until < :now)
            ORDER BY priority DESC, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
    )
    fun lockClaimable(@Param("category") category: String, @Param("now") now: Instant, @Param("limit") limit: Int): List<Long>

    @Query("select o from JpaOutboxEventEntity o where o.id in :ids order by o.priority desc, o.id")
    fun findAllByIds(@Param("ids") ids: Collection<Long>): List<JpaOutboxEventEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from JpaOutboxEventEntity o where o.eventId = :eventId")
    fun lockByEventId(@Param("eventId") eventId: String): JpaOutboxEventEntity?
}

interface AdminRequestJpaRepository : JpaRepository<JpaAdminRequestEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JpaAdminRequestEntity r where r.operation = :operation and r.idempotencyKey = :idempotencyKey")
    fun lockByScope(@Param("operation") operation: String, @Param("idempotencyKey") idempotencyKey: String): JpaAdminRequestEntity?

    /** 범위 UNIQUE에 예약(PENDING)을 삽입하거나, 이미 있으면 바꾸지 않고 그 행의 잠금을 기다려 잡는다(SELECT 후 INSERT 아님). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO admin_request (operation, idempotency_key, request_record_id, request_fingerprint, actor_ref, result_kind,
                result_code, result_reference, created_at, updated_at, version)
            VALUES (:operation, :idempotencyKey, :requestRecordId, :requestFingerprint, :actorRef, 'PENDING', NULL, NULL, :at, :at, 0)
            ON DUPLICATE KEY UPDATE version = version
        """,
    )
    @Suppress("LongParameterList")
    fun insertPendingIfAbsent(
        @Param("operation") operation: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("requestRecordId") requestRecordId: String,
        @Param("requestFingerprint") requestFingerprint: String,
        @Param("actorRef") actorRef: String,
        @Param("at") at: Instant,
    ): Int
}
