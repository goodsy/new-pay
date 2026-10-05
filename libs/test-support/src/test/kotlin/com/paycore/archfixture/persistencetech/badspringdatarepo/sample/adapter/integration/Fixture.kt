// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.badspringdatarepo.sample.adapter.integration

import org.springframework.data.repository.CrudRepository

/**
 * 기관 adapter가 Spring Data repository를 직접 주입받아 기록한다. repository 정의 위치 규칙
 * (springDataRepositoriesOnlyInPersistenceAdapter)은 Repository 하위 타입 선언만 보므로 사용처는 잡지 못한다.
 */
class VanResultRecorder(private val repository: CrudRepository<String, String>) {
    fun record(result: String): String = repository.save(result)
}
