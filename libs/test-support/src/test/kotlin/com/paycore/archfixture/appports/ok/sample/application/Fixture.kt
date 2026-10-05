// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.ok.sample.application

import com.paycore.archfixture.appports.ok.sample.application.provided.PlaceOrder
import com.paycore.archfixture.appports.ok.sample.application.required.OrderStore
import com.paycore.archfixture.appports.ok.sample.domain.Order

class PlaceOrderService(private val store: OrderStore) : PlaceOrder {
    override fun place(id: String): Order = Order(id).also(store::save)
}
