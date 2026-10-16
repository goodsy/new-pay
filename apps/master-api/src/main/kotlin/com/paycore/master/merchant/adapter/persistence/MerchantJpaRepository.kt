package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface MerchantJpaRepository : JpaRepository<JpaMerchantEntity, String>
