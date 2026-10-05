package com.paycore.modulithfixture.order.adapter.query

import com.paycore.modulithfixture.jooq.generated.tables.OrderTable

/** ModulithGeneratedCodeExclusionSpec fixture: 생성 코드를 쓰는 query adapter(테스트 소스 전용, 업무 의미 없음). */
class OrderQueryAdapter {
    fun tableName(): String = OrderTable.NAME.last().orEmpty()
}
