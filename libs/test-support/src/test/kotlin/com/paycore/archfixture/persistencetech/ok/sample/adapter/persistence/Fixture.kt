// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.ok.sample.adapter.persistence

import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant

/** 영속 기술(JPA EntityManager, Spring Data, JdbcTemplate, java.sql)은 adapter.persistence 안에서만 쓴다. */
@Entity
class OrderRow(@Id var id: String = "")

interface OrderRowRepository : CrudRepository<OrderRow, String>

class OrderStoreAdapter(
    private val entityManager: EntityManager,
    private val repository: OrderRowRepository,
    private val jdbc: JdbcTemplate,
) {
    fun save(id: String) {
        entityManager.persist(OrderRow(id))
        repository.findById(id)
        jdbc.update("update order_row set touched_at = ? where id = ?", Timestamp.from(Instant.EPOCH), id)
    }
}
