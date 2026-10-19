package com.paycore.simulator.van.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** 가상 VAN 원장 Spring Data repository(adapter.persistence 전용). */
interface VanLedgerEntryJpaRepository : JpaRepository<JpaVanLedgerEntryEntity, String> {
    fun findByClientIdAndEnvironmentAndInstitutionAccountAndOperationAndRequestId(
        clientId: String, environment: String, institutionAccount: String, operation: String, requestId: String,
    ): JpaVanLedgerEntryEntity?
}
