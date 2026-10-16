package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface CurrencyUnitJpaRepository : JpaRepository<JpaCurrencyUnitEntity, String>
