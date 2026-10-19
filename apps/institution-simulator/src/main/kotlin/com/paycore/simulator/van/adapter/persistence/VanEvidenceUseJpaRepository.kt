package com.paycore.simulator.van.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** 인증 증거 사용 Spring Data repository(adapter.persistence 전용). */
interface VanEvidenceUseJpaRepository : JpaRepository<JpaVanEvidenceUseEntity, String>
