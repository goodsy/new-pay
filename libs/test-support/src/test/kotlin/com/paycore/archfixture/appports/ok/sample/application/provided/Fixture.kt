// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.ok.sample.application.provided

import com.paycore.archfixture.appports.ok.sample.domain.Order

interface PlaceOrder {
    fun place(id: String): Order
}
