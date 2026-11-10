package com.paycore.admin.merchantops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.merchantops.adapter.web.MerchantController
import com.paycore.admin.merchantops.application.provided.MerchantLookupResult
import com.paycore.admin.merchantops.application.provided.MerchantLookupUseCase
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.TestingAuthenticationToken
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 검색 본문 크기 상한(MERCHANT-OPS-001 §5.2, Q-MOPS-08 — 사용자 답변 "거절만, 감사 없음"): 선언 길이가 없거나(chunked) 실제보다 작게 선언돼도 **상한 + 1바이트까지만** 읽고
 * 넘으면 413 `REQUEST_TOO_LARGE`(no-store), Use Case를 부르지 않는다(감사 없음 — 내용을 읽지 않았으니 시도 판정 불가), 지표만 증가. 선언 길이가 상한을 넘으면 한 바이트도 읽지 않는다.
 * MockMvc는 Content-Length를 본문에서 만들어 이 경우를 만들 수 없어 컨트롤러를 직접 부른다(HTTP 경로의 정직한 길이 사례는 `MerchantLookupHttpSpec`).
 */
class MerchantSearchBodyLimitSpec : BehaviorSpec({

    val limit = MerchantController.MAX_SEARCH_BODY_BYTES

    /** 무한히 `{"registrationNumber":"1234567890","pad":"aaaa…`를 내주며 읽은 바이트를 센다. */
    class CountingStream : ServletInputStream() {
        private val head = """{"registrationNumber":"1234567890","pad":"""".toByteArray(Charsets.UTF_8)
        var read = 0L
        override fun read(): Int = (if (read < head.size) head[read.toInt()].toInt() else 'a'.code).also { read++ }
        override fun isFinished() = false
        override fun isReady() = true
        override fun setReadListener(listener: ReadListener?) = Unit
    }

    class Request(private val declared: Long, val stream: CountingStream = CountingStream()) : MockHttpServletRequest("POST", "/api/merchant-searches") {
        init {
            contentType = "application/json"
        }

        override fun getContentLength(): Int = declared.toInt()
        override fun getContentLengthLong(): Long = declared
        override fun getInputStream(): ServletInputStream = stream
    }

    class Recording : MerchantLookupUseCase {
        val calls = mutableListOf<String>()
        override fun search(viewerActorId: String, criteria: MerchantSearchCriteria, context: AdminRequestContext) = MerchantLookupResult.InvalidInput.also { calls += "search" }
        override fun rejectMalformedSearch(viewerActorId: String, registrationNumberAttempt: Boolean, context: AdminRequestContext) =
            MerchantLookupResult.InvalidInput.also { calls += "malformed:$registrationNumberAttempt" }
        override fun detail(viewerActorId: String, merchantId: String, context: AdminRequestContext) = MerchantLookupResult.NotFound
        override fun history(viewerActorId: String, merchantId: String, afterSeq: Long?) = MerchantLookupResult.NotFound
    }

    fun call(request: Request): Triple<Int, String, Pair<Recording, SimpleMeterRegistry>> {
        val lookup = Recording()
        val registry = SimpleMeterRegistry()
        val response = MerchantController(lookup, registry, com.paycore.admin.merchantops.application.provided.MerchantPendingApprovals { _, _ -> emptyList() }).search(TestingAuthenticationToken("adm_x", null), request)
        response.headers.cacheControl!! shouldContain "no-store"
        return Triple(response.statusCode.value(), JsonMapper.builder().build().writeValueAsString(response.body), lookup to registry)
    }

    fun SimpleMeterRegistry.tooLarge() = counter(MerchantController.SEARCH_BODY_REJECTED_METRIC, "reason", "TOO_LARGE").count()

    Given("a body over 16 KiB that names registrationNumber") {
        Then("without a declared length (chunked): 413 after reading at most limit + 1 bytes; the use case is not called; counted") {
            val request = Request(declared = -1)
            val (status, body, rest) = call(request)
            status shouldBe 413
            body shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
            request.stream.read shouldBe (limit + 1).toLong()
            rest.first.calls.shouldBeEmpty()
            rest.second.tooLarge() shouldBe 1.0
        }
        Then("with an understated declared length: the bytes actually read decide — 413, at most limit + 1 bytes, no use case call") {
            val request = Request(declared = 10)
            val (status, body, rest) = call(request)
            status shouldBe 413
            body shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
            request.stream.read shouldBe (limit + 1).toLong()
            rest.first.calls.shouldBeEmpty()
            rest.second.tooLarge() shouldBe 1.0
        }
        Then("with a declared length over the limit: 413 without reading a single byte") {
            val request = Request(declared = limit + 1L)
            val (status, _, rest) = call(request)
            status shouldBe 413
            request.stream.read shouldBe 0L
            rest.first.calls.shouldBeEmpty()
            rest.second.tooLarge() shouldBe 1.0
        }
    }
})
