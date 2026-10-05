// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.baseroot.badhelper

import jakarta.persistence.EntityManager

class TxHelper(private val entityManager: EntityManager) {
    fun save(entity: Any) = entityManager.persist(entity)
}
