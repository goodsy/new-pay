// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.sharedlib.persistence

import jakarta.persistence.EntityManager

/**
 * `<base>`(appports.badoutsidebase) 밖, 같은 조직 패키지(예: com.paycore.shared)에 둔 EntityManager wrapper.
 * application이 이를 쓰면 금지 목록 방식 규칙은 우회된다(wrapper의 의존은 application 클래스의 의존이 아니다).
 */
class EntityManagerWrapper(private val entityManager: EntityManager) {
    fun save(entity: Any) = entityManager.persist(entity)
}
