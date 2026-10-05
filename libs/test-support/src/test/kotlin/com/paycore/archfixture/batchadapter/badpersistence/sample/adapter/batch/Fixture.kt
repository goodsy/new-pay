// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.batchadapter.badpersistence.sample.adapter.batch

import com.paycore.archfixture.batchadapter.badpersistence.sample.adapter.persistence.SettlementRowStore

class RowReadingStep(private val store: SettlementRowStore) {
    fun execute(): Int = store.count()
}
