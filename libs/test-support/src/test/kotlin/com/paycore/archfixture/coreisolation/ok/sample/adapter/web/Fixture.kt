// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.coreisolation.ok.sample.adapter.web

import com.paycore.archfixture.coreisolation.ok.config.WebConfig

/** adapter는 app 공통 구성을 써도 된다(이 규칙은 domain/application만 제한). */
class OrderEndpoint(private val config: WebConfig) {
    fun post(id: String): String = config.app.placeOrder.place(id)
}
