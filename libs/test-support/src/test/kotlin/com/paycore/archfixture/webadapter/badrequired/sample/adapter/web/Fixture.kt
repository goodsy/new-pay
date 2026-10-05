// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.webadapter.badrequired.sample.adapter.web

import com.paycore.archfixture.webadapter.badrequired.sample.application.required.OrderStore

class StoreEndpoint(private val store: OrderStore) {
    fun get(id: String): String? = store.find(id)
}
