// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.webadapter.badpersistence.sample.adapter.web

import com.paycore.archfixture.webadapter.badpersistence.sample.adapter.persistence.OrderRow

class RowEndpoint {
    fun get(row: OrderRow): String = row.id
}
