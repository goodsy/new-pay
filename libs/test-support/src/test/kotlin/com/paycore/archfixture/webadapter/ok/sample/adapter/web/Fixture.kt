// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.webadapter.ok.sample.adapter.web

import com.paycore.archfixture.webadapter.ok.sample.application.provided.PlaceOrder

class OrderEndpoint(private val placeOrder: PlaceOrder) {
    fun post(id: String): String = placeOrder.place(id)
}
