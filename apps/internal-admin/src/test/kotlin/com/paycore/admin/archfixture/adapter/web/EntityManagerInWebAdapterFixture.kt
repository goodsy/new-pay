package com.paycore.admin.archfixture.adapter.web

import jakarta.persistence.EntityManager

/** ArchUnit 위반 fixture(F-1, 시험 소스 전용 — bean 아님): web adapter가 EntityManager를 주입받아 Application을 우회한다. */
@Suppress("unused")
class EntityManagerInWebAdapterFixture(private val entityManager: EntityManager) {
    fun count(): Any = entityManager.createNativeQuery("SELECT 1").singleResult
}
