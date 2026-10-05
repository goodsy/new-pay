// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.badwebentitymanager.sample.adapter.web

import jakarta.persistence.EntityManager

/** Controller 계층이 EntityManager를 주입받아 Application을 우회한다. */
class EntityManagerEndpoint(private val entityManager: EntityManager) {
    fun find(id: String): Any? = entityManager.find(Any::class.java, id)
}
