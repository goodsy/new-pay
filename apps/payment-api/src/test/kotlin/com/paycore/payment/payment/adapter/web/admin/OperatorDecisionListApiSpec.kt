package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 대사용 결정 목록(ADMIN-PAYMENT-OPS-001 §4.17, D-OPS-008 — 실제 HTTP·실제 MySQL): keyset `seq > watermark` 오름차순, `recorded_at`은 DB가 INSERT 때 채움, `serverTime`은 DB 시각, 안정
 * watermark = 반환 행 중 `recorded_at ≤ serverTime − 안정 지연(시험 2초)`인 앞쪽 연속 구간의 마지막 seq. INSERT 뒤 안정 지연보다 늦게 커밋된 행은 증분에서 건너뛰고 watermark가 지나간다
 * (최종 안전망은 ADMIN 전체 스캔 — S7). 결정 행은 시험이 runtime 권한 밖 root 연결로 직접 넣는다(목록 읽기 계약만 보는 시험 — 판정 경로는 AdminOperatorCancellationApiSpec).
 */
@PaymentApiTest
class OperatorDecisionListApiSpec(private val internal: InternalConnector, private val db: DbAccess) : BehaviorSpec({

    val api = AdminOpsTestClient(internal.localPort)

    fun insert(c: java.sql.Connection, approvalId: String): Long {
        // 앱 세션과 같은 UTC(DB 기본값 NOW(3)은 세션 time_zone 기준 — 시험 서버 기본은 UTC가 아니다).
        c.createStatement().use { it.execute("SET time_zone = '+00:00'") }
        c.prepareStatement(
            "INSERT INTO operator_approval_decision (approval_id, payment_id, decision, rejection_code, content_hash, amount_minor, currency, requested_by, approved_by," +
                " approved_target_fingerprint, approved_at, execute_before, decided_at, origin) VALUES (?, ?, 'REJECTED', 'PAYMENT_NOT_FOUND', ?, 1000, 'KRW', ?, ?, ?, NOW(3)," +
                " NOW(3) + INTERVAL 1 DAY, NOW(3), 'EXECUTION_COMMAND')",
        ).use {
            it.setString(1, approvalId)
            it.setString(2, AdminOpsTestClient.newPaymentId())
            it.setString(3, "opd1:" + "0".repeat(64))
            it.setString(4, AdminOpsTestClient.REQUESTER)
            it.setString(5, AdminOpsTestClient.APPROVER)
            it.setString(6, "oct1:" + "0".repeat(64))
            it.executeUpdate()
        }
        return c.createStatement().use { st -> st.executeQuery("SELECT seq FROM operator_approval_decision WHERE approval_id = '$approvalId'").use { it.next(); it.getLong(1) } }
    }

    fun maxSeq(): Long = db.withRoot { c -> c.createStatement().use { st -> st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM operator_approval_decision").use { it.next(); it.getLong(1) } } }

    fun list(watermark: Long) = api.get("/internal/v1/admin/operator-decisions/after/$watermark")

    Given("decision rows and a late commit") {
        Then("rows come in seq order with DB recorded times; a row whose commit came later than the stable delay is skipped and the watermark passes it") {
            val start = maxSeq()
            val a = db.withRoot { c -> insert(c, AdminOpsTestClient.newApprovalId()) }
            val lateId = AdminOpsTestClient.newApprovalId()
            val inserted = CountDownLatch(1)
            val release = CountDownLatch(1)
            var lateSeq = 0L
            val late = Thread {
                db.withRoot { c ->
                    c.autoCommit = false
                    lateSeq = insert(c, lateId)
                    inserted.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    c.commit()
                }
            }.also { it.start() }
            inserted.await(30, TimeUnit.SECONDS) shouldBe true
            val c = db.withRoot { conn -> insert(conn, AdminOpsTestClient.newApprovalId()) }
            Thread.sleep(3_000) // 안정 지연(2초)보다 오래 — 늦은 행의 커밋은 그 뒤다.
            val page = list(start)
            page.status shouldBe 200
            val seqs = page.json.get("items").iterator().asSequence().map { it.get("seq").asLong() }.toList()
            seqs shouldBe listOf(a, c)
            page.json.get("nextWatermark").asLong() shouldBe c
            c shouldBeGreaterThan lateSeq
            page.json.get("items").first().has("reason") shouldBe false
            val server = Instant.parse(page.json.get("serverTime").asString())
            val dbNow = db.withRoot { conn -> conn.createStatement().use { it.execute("SET time_zone = '+00:00'") }; conn.createStatement().use { st -> st.executeQuery("SELECT DATE_FORMAT(NOW(3), '%Y-%m-%dT%H:%i:%s.%fZ')").use { it.next(); Instant.parse(it.getString(1)) } } }
            Duration.between(server, dbNow).abs().toMillis() shouldBeLessThan 5_000
            release.countDown()
            late.join()
            // 늦게 커밋된 행은 다음 증분(지나간 watermark 뒤)에 나오지 않는다 — 전체 스캔(ADMIN S7)의 몫.
            list(page.json.get("nextWatermark").asLong()).json.get("items").iterator().asSequence().none { it.get("approvalId").asString() == lateId } shouldBe true
        }
        Then("a fresh row is listed but does not advance the watermark until the stable delay passes; a malformed watermark is 400") {
            val start = maxSeq()
            val fresh = db.withRoot { c -> insert(c, AdminOpsTestClient.newApprovalId()) }
            val page = list(start)
            page.json.get("items").iterator().asSequence().map { it.get("seq").asLong() }.toList() shouldBe listOf(fresh)
            page.json.get("nextWatermark").asLong() shouldBe start
            Thread.sleep(2_500)
            list(start).json.get("nextWatermark").asLong() shouldBe fresh
            list(-1 + 0).status shouldBe 400
            api.get("/internal/v1/admin/operator-decisions/after/01").status shouldBe 400
        }
    }
})
