package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface MerchantCredentialJpaRepository : JpaRepository<JpaMerchantCredentialEntity, String>
