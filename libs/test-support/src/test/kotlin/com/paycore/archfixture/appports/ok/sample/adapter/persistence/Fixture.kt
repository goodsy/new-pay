// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.ok.sample.adapter.persistence

import com.paycore.archfixture.appports.ok.sample.application.required.OrderStore
import com.paycore.archfixture.appports.ok.sample.domain.Order

class OrderStoreJpaAdapter : OrderStore {
    val saved = mutableListOf<String>()

    override fun save(order: Order) {
        saved += order.id
    }
}
