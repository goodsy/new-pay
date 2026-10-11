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
}

interface DefaultDesignationJpaRepository : JpaRepository<JpaDefaultDesignationEntity, String>

interface SiteAssignmentJpaRepository : JpaRepository<JpaSiteAssignmentEntity, String> {
    @Query("select a from JpaSiteAssignmentEntity a where a.merchantAccountId = :accountId order by a.effectiveFrom, a.assignmentId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaSiteAssignmentEntity>
}

interface PaymentMethodActivationJpaRepository : JpaRepository<JpaPaymentMethodActivationEntity, String> {
    @Query(
        "select a.activationId from JpaPaymentMethodActivationEntity a where a.merchantAccountId = :accountId and a.paymentMethod = :method",
    )
    fun findId(@Param("accountId") accountId: String, @Param("method") method: String): String?
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
