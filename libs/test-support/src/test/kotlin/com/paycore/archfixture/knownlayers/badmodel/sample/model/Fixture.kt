// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.knownlayers.badmodel.sample.model

import jakarta.persistence.EntityManager

/** 계층 밖 `model` 패키지에서 JPA를 써 domainIsPure를 우회하는 예. */
class OrderInModelPackage(private val entityManager: EntityManager) {
    fun isOpen(): Boolean = entityManager.isOpen
}
