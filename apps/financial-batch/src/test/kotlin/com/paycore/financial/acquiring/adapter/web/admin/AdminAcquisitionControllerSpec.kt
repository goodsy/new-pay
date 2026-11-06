package com.paycore.financial.acquiring.adapter.web.admin

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.financial.acquiring.application.provided.AcquisitionFileDetail
import com.paycore.financial.acquiring.application.provided.AcquisitionFilePage
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionFileViewQuery
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordPage
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionViewFailure
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ACQ-VIEW-001 §3.2–§3.3 컨트롤러 직접 시험(Query 대역): permit 소진 → 503 `ADMIN_READ_BUSY`(Query 미호출·endpoint별 지표), 질의 시간 초과 → 503 `TEMPORARILY_UNAVAILABLE` +
 * `query_timeout{endpoint}`, 저장소 실패 → 503(예외 문구 로그 없음), 형식 위반 본문 400·형식 아닌 fileId 404(Query 미호출).
 */
class AdminAcquisitionControllerSpec : BehaviorSpec({

    val fileId = "ACQ.SIMACQA.TEST.20261105.001"

    class FakeQuery(private val failure: (() -> RuntimeException)? = null, private val block: CountDownLatch? = null, private val entered: CountDownLatch? = null) :
        AcquisitionFileViewQuery {
        var calls = 0

        private fun call() {
            calls++
            entered?.countDown()
            block?.await(10, TimeUnit.SECONDS)
            failure?.let { throw it() }
        }

        override fun search(criteria: AcquisitionFileSearchCriteria): AcquisitionFilePage {
            call()
            return AcquisitionFilePage(emptyList(), null)
        }

        override fun detail(fileId: String, records: AcquisitionRecordSearchCriteria): AcquisitionFileDetail? {
            call()
            return null
        }

        override fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria): AcquisitionRecordPage? {
            call()
            return AcquisitionRecordPage(emptyList(), null)
        }
    }

    fun post(path: String, body: String) = MockHttpServletRequest("POST", path).apply {
        servletPath = path
        setContent(body.toByteArray())
    }

    val period = """{"businessDateFrom":"2026-11-01","businessDateTo":"2026-11-05"}"""

    Given("Concurrency Control: the read permits are exhausted") {
        Then("every endpoint is 503 ADMIN_READ_BUSY at once, without calling the query, and counted per endpoint") {
            val registry = SimpleMeterRegistry()
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val holder = FakeQuery(block = release, entered = entered)
            val permits = AdminChannelPermits(FinancialAdminChannelFixture.settings(readPermits = 1), registry)
            val holding = AdminAcquisitionController(holder, permits, registry)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val running = pool.submit<Int> { holding.search(post("/internal/v1/admin/acquisition-file-searches", period)).statusCode.value() }
                entered.await(10, TimeUnit.SECONDS) shouldBe true
                val other = FakeQuery()
                val controller = AdminAcquisitionController(other, permits, registry)
                listOf(
                    controller.search(post("/internal/v1/admin/acquisition-file-searches", period)),
                    controller.detail(fileId, MockHttpServletRequest("GET", "/internal/v1/admin/acquisition-files/$fileId")),
                    controller.records(fileId, post("/internal/v1/admin/acquisition-files/$fileId/record-searches", "{}")),
                ).forEach {
                    it.statusCode.value() shouldBe 503
                    String(it.body!!) shouldBe """{"error":"ADMIN_READ_BUSY"}"""
                }
                other.calls shouldBe 0
                listOf(AdminAcquisitionController.FILE_SEARCH, AdminAcquisitionController.FILE_DETAIL, AdminAcquisitionController.RECORD_SEARCH).forEach {
                    registry.counter(AdminChannelPermits.BUSY_METRIC, "endpoint", it).count() shouldBe 1.0
                }
                release.countDown()
                running.get(10, TimeUnit.SECONDS) shouldBe 200
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    Given("Recoverability: the store times out or fails") {
        Then("each is 503 TEMPORARILY_UNAVAILABLE; a timeout is counted per endpoint; the exception message is never logged") {
            val logger = LoggerFactory.getLogger(AdminAcquisitionController::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            try {
                val registry = SimpleMeterRegistry()
                val secret = "driver-said-ACQ.SIMACQA.TEST.20261105.001-mid_demo"
                val timeout = AdminAcquisitionController(FakeQuery({ AcquisitionViewFailure.TimedOut() }), AdminChannelPermits(FinancialAdminChannelFixture.settings(), registry), registry)
                val broken = AdminAcquisitionController(FakeQuery({ IllegalStateException(secret) }), AdminChannelPermits(FinancialAdminChannelFixture.settings(), registry), registry)
                listOf(
                    timeout.search(post("/internal/v1/admin/acquisition-file-searches", period)),
                    timeout.detail(fileId, MockHttpServletRequest("GET", "/x")),
                    timeout.records(fileId, post("/x", "{}")),
                    broken.search(post("/internal/v1/admin/acquisition-file-searches", period)),
                    broken.detail(fileId, MockHttpServletRequest("GET", "/x")),
                ).forEach {
                    it.statusCode.value() shouldBe 503
                    String(it.body!!) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                }
                listOf(AdminAcquisitionController.FILE_SEARCH, AdminAcquisitionController.FILE_DETAIL, AdminAcquisitionController.RECORD_SEARCH).forEach {
                    registry.counter(AdminAcquisitionController.TIMEOUT_METRIC, "endpoint", it).count() shouldBe 1.0
                }
                (appender.list.size >= 5) shouldBe true
                appender.list.forEach {
                    it.formattedMessage shouldNotContain secret
                    it.formattedMessage shouldNotContain fileId
                }
            } finally {
                logger.detachAppender(appender)
            }
        }
    }

    Given("malformed input") {
        Then("a malformed search body is 400 REQUEST_MALFORMED and a non-file-id path is 404, without calling the query") {
            val registry = SimpleMeterRegistry()
            val query = FakeQuery()
            val controller = AdminAcquisitionController(query, AdminChannelPermits(FinancialAdminChannelFixture.settings(), registry), registry)
            controller.search(post("/x", """{"environment":"TEST"}""")).let {
                it.statusCode.value() shouldBe 400
                String(it.body!!) shouldBe """{"error":"REQUEST_MALFORMED"}"""
            }
            controller.records(fileId, post("/x", """{"limit":0}""")).statusCode.value() shouldBe 400
            listOf("x", "ACQ.SIMACQA.TEST.20261105.1", "..%2F").forEach { bad ->
                controller.detail(bad, MockHttpServletRequest("GET", "/x")).let {
                    it.statusCode.value() shouldBe 404
                    String(it.body!!) shouldBe """{"error":"ACQUISITION_FILE_NOT_FOUND"}"""
                }
                controller.records(bad, post("/x", "{}")).statusCode.value() shouldBe 404
            }
            controller.detail(fileId, MockHttpServletRequest("GET", "/x")).statusCode.value() shouldBe 404
            query.calls shouldBe 1
        }
    }
})
