// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.repositories.ok.sample.adapter.persistence

import org.springframework.data.repository.CrudRepository

class OrderRow(val id: String)

interface OrderRowRepository : CrudRepository<OrderRow, String>
