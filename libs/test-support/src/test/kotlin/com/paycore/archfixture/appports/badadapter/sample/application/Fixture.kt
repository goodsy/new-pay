// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.badadapter.sample.application

import com.paycore.archfixture.appports.badadapter.sample.adapter.persistence.OrderJpaRow

class LeakyService {
    fun load(id: String): OrderJpaRow = OrderJpaRow(id)
}
