package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.KEY
import com.paycore.payment.entitlement.EntitlementEnvelopes.ROTATION_KEY
import com.paycore.payment.entitlement.EntitlementEnvelopes.ROTATION_KEY_ID
import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.envelope
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.EntitlementEnvelopes.signature
import com.paycore.payment.entitlement.adapter.integration.MicrometerEntitlementMetrics
import com.paycore.payment.entitlement.domain.ActivationEntry
import com.paycore.payment.entitlement.domain.ActivationStatus
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapAccount
import com.paycore.payment.payment.adapter.persistence.DbAccess
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * PAYMENT 내부 Inbox(MASTER-SNAPSHOT-001 명세 §3.5, §5.1, §7)를 실제 MySQL(runtime 계정)과 실제 web adapter(MockMvc)로 확인한다:
 * 서명 검증(시험 키, 2키 교체)·봉투/내용 계약 오류는 저장 없이 4xx, 저장은 Inbox + 업무 반영 한 로컬 TX, 응답(ACK)은 커밋 뒤,
 * 같은 eventId 재전송 DUPLICATE, 순서 역전 STALE(R2), 미지원 버전·무결성 충돌은 head를 막는다(fail-closed).
 */
@EntitlementDbTest
class EntitlementInboxSpec(
    private val context: WebApplicationContext,
    private val db: DbAccess,
    private val faults: ProjectionFaultInjector,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).build()

    afterEach { faults.reset() }

    data class Stored(val inbox: Long, val snapshots: Long, val owners: Long, val headRevision: Long?, val blocked: String?)

    fun stored(merchantId: String): Stored = db.withApp { c ->
        with(db) {
            Stored(
                inbox = (c.scalar("SELECT COUNT(*) FROM inbox_event WHERE aggregate_id = ?", merchantId) as Number).toLong(),
                snapshots = (c.scalar("SELECT COUNT(*) FROM entitlement_snapshot WHERE merchant_id = ?", merchantId) as Number).toLong(),
                owners = (c.scalar("SELECT COUNT(*) FROM entitlement_mid_owner WHERE merchant_id = ?", merchantId) as Number).toLong(),
                headRevision = (c.scalar("SELECT revision FROM entitlement_head WHERE merchant_id = ?", merchantId) as Number?)?.toLong(),
                blocked = c.scalar("SELECT blocked_reason FROM entitlement_head WHERE merchant_id = ?", merchantId) as String?,
            )
        }
    }

    fun ok(response: EntitlementEnvelopes.Response): Pair<String, Long> {
        response.status shouldBe 200
        return response.json!!.get("outcome").stringValue() to response.json.get("appliedRevision").longValue()
    }

    Given("a signed snapshot for a new merchant") {
        Then("it is APPLIED after commit: inbox, revision content, MID owner and head are stored together; the ACK carries consumer and revision") {
            val content = SnapshotFixture.entitled(revision = 3)
            val b = body(content, SnapshotFixture.leaseAt(T0))
            val response = send(mvc, b)
            ok(response) shouldBe ("APPLIED" to 3L)
            response.json!!.get("consumer").stringValue() shouldBe "payment-api"
            stored(content.merchantId) shouldBe Stored(1, 1, 1, 3, null)
        }

        Then("the same eventId is answered DUPLICATE with the current head revision and stores nothing new; another body under that eventId is 409") {
            val content = SnapshotFixture.entitled()
            val b = body(content, SnapshotFixture.leaseAt(T0))
            ok(send(mvc, b))
            val duplicate = send(mvc, b)
            ok(duplicate) shouldBe ("DUPLICATE" to 1L)
            // review m-3: 재전송 응답은 처음 저장한 outcome을 함께 준다.
            duplicate.json!!.get("originalOutcome").stringValue() shouldBe "APPLIED"
            val other = body(SnapshotFixture.entitled(revision = 2), SnapshotFixture.leaseAt(T0), eventId = EntitlementEnvelopes.eventIdOf(b))
            send(mvc, other).status shouldBe 409
            stored(content.merchantId).inbox shouldBe 1
        }

        Then("missing, malformed, unknown-key or wrong signatures are 401 and nothing is stored; the rotation key is accepted") {
            val content = SnapshotFixture.entitled()
            val b = body(content, SnapshotFixture.leaseAt(T0))
            val before = registry.find(MicrometerEntitlementMetrics.INBOX_REJECTED).tags("kind", "SIGNATURE_INVALID").counter()?.count() ?: 0.0
            listOf(
                null, "v1=abc", signature(b, keyId = "unknown", key = KEY), signature(b, key = ROTATION_KEY), signature(b + ' '.code.toByte()),
            ).map { send(mvc, b, it).status } shouldBe listOf(401, 401, 401, 401, 401)
            stored(content.merchantId).headRevision shouldBe null
            (registry.find(MicrometerEntitlementMetrics.INBOX_REJECTED).tags("kind", "SIGNATURE_INVALID").counter()!!.count() - before) shouldBe 5.0
            ok(send(mvc, b, signature(b, ROTATION_KEY_ID, ROTATION_KEY))) shouldBe ("APPLIED" to 1L)
        }

        Then("the contract golden envelope produced by MASTER rules (S7) is accepted with its golden signature and APPLIED at revision 7") {
            val golden = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(org.springframework.core.io.ClassPathResource("contracts/entitlement-snapshot-v1.golden.json").inputStream)
            val b = golden.get("envelopeBody").stringValue().toByteArray()
            val response = send(mvc, b, golden.get("signatureHeader").stringValue())
            ok(response) shouldBe ("APPLIED" to 7L)
            db.withApp { c -> with(db) { c.scalar("SELECT content_hash FROM entitlement_head WHERE merchant_id = ?", golden.get("merchantId").stringValue()) } } shouldBe
                golden.get("contentHash").stringValue()
        }

        Then("an unreadable envelope is 400 and a content hash that does not match the content is 422 (nothing stored)") {
            val content = SnapshotFixture.entitled()
            val garbage = "{not json".toByteArray()
            send(mvc, garbage, signature(garbage)).status shouldBe 400
            val forged = body(content, SnapshotFixture.leaseAt(T0), contentHash = "ec1:" + "0".repeat(64))
            send(mvc, forged).status shouldBe 422
            stored(content.merchantId).headRevision shouldBe null
        }
    }

    Given("revisions arriving out of order (R2)") {
        Then("r+1 (blocking) first and r (an older permission) later: r is STALE and the head stays at r+1") {
            val ids = SnapshotFixture.ids()
            val allowed = SnapshotFixture.entitled(ids, revision = 1)
            val blocking = SnapshotFixture.entitled(ids, revision = 2).mapAccount { it.copy(activations = listOf(ActivationEntry("CARD", ActivationStatus.SUSPENDED))) }
            ok(send(mvc, body(blocking, SnapshotFixture.leaseAt(T0)))) shouldBe ("APPLIED" to 2L)
            ok(send(mvc, body(allowed, SnapshotFixture.leaseAt(T0)))) shouldBe ("STALE" to 2L)
            stored(ids.merchantId).let { it.headRevision to it.snapshots } shouldBe (2L to 1L)
        }

        Then("a renewal of the same revision with a longer lease is LEASE_EXTENDED; an older renewal arriving after a newer revision is ignored") {
            val ids = SnapshotFixture.ids()
            val r1 = SnapshotFixture.entitled(ids, revision = 1)
            ok(send(mvc, body(r1, SnapshotFixture.leaseAt(T0))))
            ok(send(mvc, body(r1, SnapshotFixture.leaseAt(T0.plusSeconds(30), seq = 1)))) shouldBe ("LEASE_EXTENDED" to 1L)
            ok(send(mvc, body(SnapshotFixture.entitled(ids, revision = 2), SnapshotFixture.leaseAt(T0.plusSeconds(31)))))
            ok(send(mvc, body(r1, SnapshotFixture.leaseAt(T0.plusSeconds(40), seq = 2)))) shouldBe ("STALE" to 2L)
        }

        Then("an unsupported newer schema version blocks the head (VERSION_UNSUPPORTED) and keeps the raw envelope; a same-revision different hash is INTEGRITY_CONFLICT") {
            val ids = SnapshotFixture.ids()
            val r1 = SnapshotFixture.entitled(ids, revision = 1)
            ok(send(mvc, body(r1, SnapshotFixture.leaseAt(T0))))
            val v2 = envelope(UUID.randomUUID().toString(), 2, ids.merchantId, 2, """{"futureField":true}""")
            ok(send(mvc, v2, signature(v2))) shouldBe ("UNSUPPORTED" to 1L)
            stored(ids.merchantId).blocked shouldBe "VERSION_UNSUPPORTED"
            db.withApp { c -> with(db) { c.scalar("SELECT JSON_EXTRACT(payload, '$.payload.futureField') FROM inbox_event WHERE aggregate_id = ? AND outcome = 'UNSUPPORTED'", ids.merchantId) } }
                .toString() shouldBe "true"

            val other = SnapshotFixture.ids()
            val o1 = SnapshotFixture.entitled(other, revision = 1)
            ok(send(mvc, body(o1, SnapshotFixture.leaseAt(T0))))
            ok(send(mvc, body(o1.mapAccount { it.copy(blocked = true) }, SnapshotFixture.leaseAt(T0)))) shouldBe ("INTEGRITY_CONFLICT" to 1L)
            stored(other.merchantId).blocked shouldBe "INTEGRITY_CONFLICT"
        }
    }

    Given("concurrent deliveries (real MySQL, separate connections)") {
        Then("the same eventId sent 8 times at once is APPLIED once and DUPLICATE 7 times with one inbox row") {
            val content = SnapshotFixture.entitled()
            val b = body(content, SnapshotFixture.leaseAt(T0))
            val outcomes = concurrently(8) { send(mvc, b) }.map { ok(it.getOrThrow()).first }
            outcomes.groupingBy { it }.eachCount() shouldBe mapOf("APPLIED" to 1, "DUPLICATE" to 7)
            stored(content.merchantId).inbox shouldBe 1
        }

        Then("revisions r and r+1 delivered at the same time in both orders always end with head r+1") {
            repeat(2) { order ->
                val ids = SnapshotFixture.ids()
                val bodies = listOf(1L, 2L).map { body(SnapshotFixture.entitled(ids, revision = it), SnapshotFixture.leaseAt(T0)) }
                val sendOrder = if (order == 0) bodies else bodies.reversed()
                concurrently(2) { send(mvc, sendOrder[it]) }.forEach { it.getOrThrow().status shouldBe 200 }
                stored(ids.merchantId).headRevision shouldBe 2L
            }
        }
    }

    Given("a failure injected after each storage point of the inbox transaction") {
        listOf(ProjectionFaultPoint.INBOX_INSERT, ProjectionFaultPoint.SNAPSHOT_INSERT, ProjectionFaultPoint.MID_OWNER, ProjectionFaultPoint.HEAD_UPDATE).forEach { point ->
            Then("a failure after $point stores nothing (no ACK — 5xx) and the resent event is then APPLIED") {
                val content = SnapshotFixture.entitled()
                val b = body(content, SnapshotFixture.leaseAt(T0))
                faults.after(point) { throw IllegalStateException("injected after $point") }
                runCatching { send(mvc, b) }.getOrNull()?.status?.let { (it >= 500) shouldBe true }
                stored(content.merchantId).let { Triple(it.inbox, it.snapshots, it.owners) } shouldBe Triple(0L, 0L, 0L)
                stored(content.merchantId).headRevision.let { (it == null || it == 0L) shouldBe true }
                faults.reset()
                ok(send(mvc, b)) shouldBe ("APPLIED" to 1L)
            }
        }

        Then("a failure at commit (outcome unknown) answers 503 without an ACK; the resend converges to APPLIED") {
            val content = SnapshotFixture.entitled()
            val b = body(content, SnapshotFixture.leaseAt(T0))
            faults.after(ProjectionFaultPoint.HEAD_UPDATE) {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = throw IllegalStateException("injected beforeCommit")
                    },
                )
            }
            send(mvc, b).status shouldBe 503
            stored(content.merchantId).inbox shouldBe 0
            faults.reset()
            ok(send(mvc, b)) shouldBe ("APPLIED" to 1L)
        }
    }
})

/** [n]개 작업을 같은 출발선에서 실행한다(각자 자기 연결·TX). */
fun <T> concurrently(n: Int, task: (Int) -> T): List<Result<T>> {
    val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
    val start = java.util.concurrent.CountDownLatch(1)
    try {
        val futures = (0 until n).map { i -> pool.submit<Result<T>> { start.await(); runCatching { task(i) } } }
        start.countDown()
        return futures.map { it.get(90, java.util.concurrent.TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}
