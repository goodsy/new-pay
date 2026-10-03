package com.paycore.payment.techsmoke

import com.paycore.techsmoke.payment.TechSmokeItemEntity
import com.paycore.techsmoke.payment.TechSmokeItemRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * JPA(Hibernate 7) + 실제 MySQL 8.4 기술 조합 smoke: 저장/조회, @Version 낙관적 잠금 충돌, 예외 시 rollback.
 * 각 test는 서로 다른 id를 써서 순서와 무관하다. 업무 규칙은 검사하지 않는다.
 */
@TechSmokeTest
class TechSmokeJpaSpec(
    private val repository: TechSmokeItemRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
) : FunSpec({

    val tx = TransactionTemplate(transactionManager)
    val independentTx = TransactionTemplate(transactionManager).apply {
        // 바깥 TX를 잠시 멈추고 별도 DB 연결·별도 영속성 컨텍스트로 새 TX를 연다.
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun committedRow(id: Long): Map<String, Any?>? =
        jdbcTemplate.queryForList("SELECT name, version FROM tech_smoke_item WHERE id = ?", id).singleOrNull()

    test("insert then read back in a new transaction") {
        tx.executeWithoutResult { repository.save(TechSmokeItemEntity(id = 1, name = "first")) }

        val loaded = tx.execute { repository.findById(1L).orElseThrow() }!!

        loaded.name shouldBe "first"
        // INSERT 시 Hibernate가 version을 0으로 넣는다.
        loaded.version shouldBe 0L
    }

    test("optimistic lock: stale transaction fails at commit and the first committed value remains") {
        tx.executeWithoutResult { repository.save(TechSmokeItemEntity(id = 2, name = "original")) }

        shouldThrow<OptimisticLockingFailureException> {
            tx.executeWithoutResult {
                // TX A: version 0 상태로 읽어 영속성 컨텍스트에 둔다.
                val staleInA = repository.findById(2L).orElseThrow()

                // TX B(독립): 같은 행을 읽어 수정하고 먼저 commit한다. save 호출 없이 변경 감지가 UPDATE를 만든다 → version 1.
                independentTx.executeWithoutResult {
                    repository.findById(2L).orElseThrow().name = "committed-by-B"
                }

                // TX A: 변경 감지로 commit 시 `UPDATE ... WHERE id = 2 AND version = 0`이 실행되어 0행 → 충돌.
                staleInA.name = "stale-write-by-A"
            }
        }

        val row = committedRow(2)!!
        row["name"] shouldBe "committed-by-B"
        (row["version"] as Number).toLong() shouldBe 1L
    }

    test("exception after flush inside TransactionTemplate rolls the insert back") {
        shouldThrow<IllegalStateException> {
            tx.executeWithoutResult {
                repository.saveAndFlush(TechSmokeItemEntity(id = 3, name = "to-be-rolled-back"))
                // flush 후에는 INSERT가 이 TX의 DB 연결에서 실제로 실행되었다(같은 TX에 참여한 JdbcTemplate으로 확인).
                committedRow(3)?.get("name") shouldBe "to-be-rolled-back"
                throw IllegalStateException("injected failure after flush")
            }
        }

        committedRow(3) shouldBe null
    }
})
