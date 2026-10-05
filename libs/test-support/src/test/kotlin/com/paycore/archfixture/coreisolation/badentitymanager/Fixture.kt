// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.coreisolation.badentitymanager

import jakarta.persistence.EntityManager

/** <base> 바로 아래에서 EntityManager를 감싼 helper. application이 이를 쓰면 Port 규칙을 우회한다. */
class TxHelper(private val entityManager: EntityManager) {
    fun save(entity: Any) = entityManager.persist(entity)
}
