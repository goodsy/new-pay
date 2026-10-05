// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.batchadapter.badrequired.sample.adapter.batch

import com.paycore.archfixture.batchadapter.badrequired.sample.application.required.SettlementStore

class PortBypassingStep(private val store: SettlementStore) {
    fun execute(): Int = store.pending()
}
