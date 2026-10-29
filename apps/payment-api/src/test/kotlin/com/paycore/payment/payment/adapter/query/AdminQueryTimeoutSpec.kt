package com.paycore.payment.payment.adapter.query

import com.paycore.payment.payment.adapter.persistence.SqlCapture
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient
import com.paycore.payment.payment.application.required.QueryTimedOut
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.PaymentId
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource
import io.kotest.assertions.fail
import io.kotest.matchers.types.shouldBeInstanceOf
import com.paycore.payment.payment.adapter.persistence.DbAccess
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 관리 조회 질의 시간 상한(ADMIN-PAYMENT-OPS-001 §4.7 M-7, I-09 실측 — 실제 MySQL 8.4.11).
 * - (review M-2 a) **운영 Reader 메서드마다** 실제로 실행된 SQL에 `MAX_EXECUTION_TIME(2000)` hint가 있다(jOOQ ExecuteListener 캡처 — 상수가 아니라 실행 SQL을 본다).
 * - 관리 조회와 같은 형태로 2초를 넘는 SELECT는 서버가 중단(3024)하고 [QueryTimedOut]으로 옮겨지며 연결은 풀로 돌아간다. hint가 빠지는 회귀는 15초 뒤 시험 질의를 KILL QUERY하고 FAILED가 된다(M-2 b).
 */
@PaymentApiTest
class AdminQueryTimeoutSpec(
    dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
    dataSource: DataSource,
    capture: SqlCapture,
    decisions: JooqOperatorDecisionReader,
    views: JooqCancellationViewReader,
    db: DbAccess,
) : BehaviorSpec({

    val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    val pool = dataSource.unwrap(HikariDataSource::class.java)

    Given("every production admin read") {
        Then("each executed SELECT carries MAX_EXECUTION_TIME(2000)") {
            val apr = OperatorApprovalId(AdminOpsTestClient.newApprovalId())
            mapOf<String, () -> Unit>(
                "decision find" to { decisions.find(apr) },
                "decision findLegacy" to { decisions.findLegacy(apr) },
                "decision after" to { decisions.after(0, 1) },
                "target findTarget" to { views.findTarget(PaymentId(AdminOpsTestClient.newPaymentId())) },
            ).forEach { (name, call) ->
                withClue(name) {
                    val sqls = capture.capture(call)
                    sqls.shouldNotBeEmpty()
                    sqls.forEach { it shouldContain "MAX_EXECUTION_TIME(2000)" }
                }
            }
        }
    }

    Given("a management read slower than its execution time limit") {
        Then("the server stops it at about 2 seconds (3024 -> QueryTimedOut) and the connection goes back to the pool") {
            val started = System.nanoTime()
            // review M-2 b: JDBC 호출은 kotest timeout으로 끊기지 않는다(blockingTest도 — 실측 10분 기본값까지 대기). 별도 스레드에서 실행하고 15초 안에 끝나지 않으면 시험 질의를 root로
            // KILL QUERY한 뒤 FAILED — hint 회귀가 환경 timeout이 아니라 시험 실패로 끝난다.
            val executor = Executors.newSingleThreadExecutor()
            val slow = executor.submit<Throwable?> {
                runCatching {
                    readTx.execute {
                        AdminReadLimits.guard {
                            dsl.select(DSL.count()).hint(AdminReadLimits.HINT)
                                .from(DSL.table("information_schema.COLUMNS").`as`("a"), DSL.table("information_schema.COLUMNS").`as`("b"), DSL.table("information_schema.COLUMNS").`as`("c"))
                                .fetchOne(0, Long::class.java)
                        }
                    }
                }.exceptionOrNull()
            }
            val failure = try {
                slow.get(15, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                db.withRoot { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT ID FROM information_schema.PROCESSLIST WHERE INFO LIKE '%information_schema.COLUMNS%' AND INFO NOT LIKE '%PROCESSLIST%'").use { rs ->
                            val ids = generateSequence { if (rs.next()) rs.getLong(1) else null }.toList()
                            ids.forEach { id -> c.createStatement().use { it.execute("KILL QUERY $id") } }
                        }
                    }
                }
                slow.get(30, TimeUnit.SECONDS)
                fail("the admin read was not stopped by the server within 15 seconds (MAX_EXECUTION_TIME hint missing?)")
            } finally {
                executor.shutdown()
            }
            failure.shouldBeInstanceOf<QueryTimedOut>()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            elapsedMillis shouldBeLessThan 4_000L
            readTx.execute { dsl.selectOne().fetchOne(0, Int::class.java) } shouldBe 1
            pool.hikariPoolMXBean.activeConnections shouldBe 0
        }
    }
})
