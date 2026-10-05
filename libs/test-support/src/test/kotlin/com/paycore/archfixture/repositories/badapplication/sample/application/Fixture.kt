// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.repositories.badapplication.sample.application

import org.springframework.data.repository.Repository

interface RepositoryInApplication : Repository<String, String>
