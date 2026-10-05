// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badjpa.sample.domain

import jakarta.persistence.Entity

@Entity
class JpaAnnotatedOrder(val id: String)
