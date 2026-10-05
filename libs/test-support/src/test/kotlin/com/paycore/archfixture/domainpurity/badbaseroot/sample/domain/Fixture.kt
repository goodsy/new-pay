// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badbaseroot.sample.domain

import com.paycore.archfixture.domainpurity.badbaseroot.CoreComponent

@CoreComponent
class StereotypedOrder(val id: String)
