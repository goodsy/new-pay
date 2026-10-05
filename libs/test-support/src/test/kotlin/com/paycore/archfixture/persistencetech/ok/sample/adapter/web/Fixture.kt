// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.ok.sample.adapter.web

import com.paycore.archfixture.persistencetech.ok.sample.application.OrderService

class OrderEndpoint(private val service: OrderService) {
    fun place(id: String): String = service.place(id)
}
