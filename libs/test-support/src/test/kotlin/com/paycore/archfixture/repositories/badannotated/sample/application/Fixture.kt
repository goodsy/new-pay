// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.repositories.badannotated.sample.application

import org.springframework.data.repository.RepositoryDefinition

@RepositoryDefinition(domainClass = String::class, idClass = String::class)
interface AnnotatedRepositoryInApplication {
    fun findById(id: String): String?
}
