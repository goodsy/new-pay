package com.paycore.techsmoke.payment

import org.springframework.data.jpa.repository.JpaRepository

/** TECH-001 smoke 전용 Spring Data repository(테스트 소스에만 존재). */
interface TechSmokeItemRepository : JpaRepository<TechSmokeItemEntity, Long>
