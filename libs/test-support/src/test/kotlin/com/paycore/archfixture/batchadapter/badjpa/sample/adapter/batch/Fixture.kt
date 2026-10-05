// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.batchadapter.badjpa.sample.adapter.batch

import jakarta.persistence.EntityManagerFactory

/** JPA를 Batch 배선에서 직접 쓰는 예(JpaPagingItemReader 방식). */
class JpaReadingStep(private val entityManagerFactory: EntityManagerFactory) {
    fun execute(): Boolean = entityManagerFactory.isOpen
}
