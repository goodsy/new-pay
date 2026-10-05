// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.batchadapter.badspringdata.sample.adapter.batch

import org.springframework.data.repository.CrudRepository

/** Spring Data Repository를 Batch 배선에서 직접 쓰는 예(RepositoryItemReader 방식). */
class RepositoryReadingStep(private val repository: CrudRepository<String, String>) {
    fun execute(): Long = repository.count()
}
