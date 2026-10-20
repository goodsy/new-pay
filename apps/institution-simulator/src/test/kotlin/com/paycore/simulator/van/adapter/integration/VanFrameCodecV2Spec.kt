package com.paycore.simulator.van.adapter.integration

import com.paycore.simulator.van.domain.VanCancelScope
import com.paycore.simulator.van.domain.VanDecisionOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat

/**
 * sim-van-2 계약(CANCEL-001 §6.1·§6.2, golden `van-contract-sim-van-2.golden.json` — harness가 두 앱과 독립 생성). sim-van-1 golden 파일은 sha256으로 고정이고(m-7), v2 안의
 * sim-van-1 벡터는 v1과 바이트가 같다. 취소 요청 4종은 엄격 파싱(닫힌 필드 집합·첫 필드 type·null 금지), 응답은 계약 필드 순서의 compact JSON — 취소 승인번호가 없으면 필드를
 * 생략한다(n-5: null 금지 규칙).
 */
class VanFrameCodecV2Spec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val v1Bytes = ClassPathResource("contracts/van-contract-sim-van-1.golden.json").inputStream.readAllBytes()
    val v1 = mapper.readTree(v1Bytes)
    val golden = mapper.readTree(ClassPathResource("contracts/van-contract-sim-van-2.golden.json").inputStream)
    val keyId = golden.get("hmac").get("keyId").stringValue()
    val key = Base64.getDecoder().decode(golden.get("hmac").get("keyBase64").stringValue())
    val keys = VanKeyRing(mapOf(keyId to VanKeyRing.Key("pg-demo", key)))

    fun message(name: String): JsonNode = golden.get("messages").get(name)
    fun compact(node: JsonNode): ByteArray = mapper.writeValueAsBytes(node)
    fun hex(name: String): ByteArray = HexFormat.of().parseHex(golden.get("frames").get(name).stringValue())

    Given("the sim-van-1 golden pinned by m-7") {
        Then("the sim-van-1 golden file is byte-for-byte the reviewed contract (sha256 pin)") {
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v1Bytes)) shouldBe
                "0a7aaa20f1e02905b001cd875b70f88972c6454e893c1f909cea58d872ccf412"
        }
        Then("every sim-van-1 message, hash and frame is carried into sim-van-2 unchanged") {
            listOf("messages", "payloadHashes", "frames").forEach { part ->
                v1.get(part).properties().forEach { (name, value) -> golden.get(part).get(name) shouldBe value }
            }
            golden.get("contractVersion").stringValue() shouldBe "sim-van-2"
            v1.get("contractVersion").stringValue() shouldBe "sim-van-1"
        }
    }

    Given("the golden cancel request messages") {
        Then("a CANCEL_REQUEST parses into the business request; its payload hash equals the golden canonical hash") {
            val parsed = VanMessages.parseRequest(compact(message("cancelRequest"))) as VanInbound.Cancel
            parsed.messageId shouldBe "01JA0000000000000000000030"
            parsed.requestedAt shouldBe Instant.parse("2026-10-17T02:00:00.000Z")
            val r = parsed.request
            r.requestId shouldBe "cr_01JA0000000000000000000021"
            r.originalRequestId shouldBe "ar_01JA0000000000000000000001"
            r.originalProviderTransactionId shouldBe "SIMPTX-0000000001"
            r.originalApprovalNumber shouldBe "30000001"
            r.originalTransactionDate shouldBe LocalDate.parse("2026-10-16")
            r.cancelAmountMinor shouldBe 3_000
            r.scope shouldBe VanCancelScope.PARTIAL
            r.payloadHash() shouldBe golden.get("payloadHashes").get("cancelRequest").stringValue()
            val full = (VanMessages.parseRequest(compact(message("cancelRequestFull"))) as VanInbound.Cancel).request
            full.payloadHash() shouldBe golden.get("payloadHashes").get("cancelRequestFull").stringValue()
        }
        Then("a resent cancel (other messageId and requestedAt) is the same business request with the same hash") {
            val first = VanMessages.parseRequest(compact(message("cancelRequest"))) as VanInbound.Cancel
            val resent = VanMessages.parseRequest(compact(message("cancelRequestResent"))) as VanInbound.Cancel
            resent.request shouldBe first.request
            resent.request.payloadHash() shouldBe first.request.payloadHash()
        }
        Then("a CANCEL_INQUIRY_REQUEST parses with the original cancel request ID and its closeIfAbsent flag") {
            val q = VanMessages.parseRequest(compact(message("cancelInquiryRequestClose"))) as VanInbound.CancelInquiry
            q.inquiryId shouldBe "cq_01JA0000000000000000000022"
            q.originalRequestId shouldBe "cr_01JA0000000000000000000021"
            q.institutionAccount shouldBe "SIMMERCH0001"
            q.environment shouldBe "TEST"
            q.closeIfAbsent shouldBe true
            (VanMessages.parseRequest(compact(message("cancelInquiryRequest"))) as VanInbound.CancelInquiry).closeIfAbsent shouldBe false
        }
        Then("unknown fields, null, wrong types, missing fields, bad ids, a bad scope or date, or a response type are FRAMING") {
            fun mutated(name: String, change: (ObjectNode) -> Unit): ByteArray = compact((message(name).deepCopy() as ObjectNode).also(change))
            listOf(
                mutated("cancelRequest") { it.put("extra", true) },
                mutated("cancelRequest") { it.putNull("originalApprovalNumber") },
                mutated("cancelRequest") { it.put("cancelAmountMinor", "3000") },
                mutated("cancelRequest") { it.put("cancelAmountMinor", 0) },
                mutated("cancelRequest") { it.remove("scope") },
                mutated("cancelRequest") { it.put("scope", "HALF") },
                mutated("cancelRequest") { it.put("originalTransactionDate", "2026-13-01") },
                mutated("cancelRequest") { it.put("requestId", "ar_01JA0000000000000000000021") },
                mutated("cancelRequest") { it.put("originalRequestId", "cr_01JA0000000000000000000001") },
                mutated("cancelInquiryRequest") { it.put("originalRequestId", "ar_01JA0000000000000000000001") },
                mutated("cancelInquiryRequest") { it.put("inquiryId", "aq_01JA0000000000000000000022") },
                mutated("cancelInquiryRequest") { it.put("closeIfAbsent", "true") },
                compact(message("cancelResponseApproved")),
                compact(message("cancelInquiryResponseFound")),
            ).forEach { payload ->
                shouldThrow<VanFrameException> { VanMessages.parseRequest(payload) }.failure shouldBe VanFrameFailure.FRAMING
            }
        }
        Then("the golden cancel request frame is the HMAC frame of the compact payload") {
            VanFrameCodec.encode(keyId, key, compact(message("cancelRequest"))) shouldBe hex("cancelRequest")
            VanFrameCodec.read(ByteArrayInputStream(hex("cancelResponseApproved")), keys).payload shouldBe compact(message("cancelResponseApproved"))
        }
    }

    Given("cancel responses written by the VAN") {
        val reply = "01JA0000000000000000000030"
        val rid = "cr_01JA0000000000000000000021"
        val at = Instant.parse("2026-10-17T02:00:00.150Z")
        val decisionId = "vd_01JA0000000000000000000023"
        val terms = VanMessages.CancelTerms(
            cancelApprovalNumber = "40000001", providerTransactionId = "SIMPTX-C000000001", originalProviderTransactionId = "SIMPTX-0000000001",
            cancelledAmountMinor = 3_000, currency = "KRW", institutionTransactionDate = LocalDate.parse("2026-10-17"), institutionBusinessDate = LocalDate.parse("2026-10-17"),
        )
        Then("every CANCEL_RESPONSE is byte-identical to the golden message; an absent cancel approval number is omitted (n-5)") {
            VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.APPROVED, decisionId, null, terms, null, at) shouldBe compact(message("cancelResponseApproved"))
            VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.APPROVED, decisionId, null, terms.copy(cancelApprovalNumber = null), null, at) shouldBe
                compact(message("cancelResponseApprovedWithoutApprovalNumber"))
            VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.DECLINED, decisionId, null, null, "EXCEEDS_REMAINING", at) shouldBe
                compact(message("cancelResponseDeclined"))
            VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.PENDING, null, "vp_01JA0000000000000000000025", null, null, at) shouldBe
                compact(message("cancelResponsePending"))
            VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.REQUEST_CONFLICT, null, null, null, null, at) shouldBe compact(message("cancelResponseRequestConflict"))
            VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.REJECTED_CLOSED, null, null, null, null, at) shouldBe compact(message("cancelResponseRejectedClosed"))
        }
        Then("every CANCEL_INQUIRY_RESPONSE is byte-identical to the golden message") {
            val qr = "01JA0000000000000000000031"
            val qid = "cq_01JA0000000000000000000022"
            val q = Instant.parse("2026-10-17T02:05:00.000Z")
            val found = VanMessages.InquiryResult.FOUND
            VanMessages.cancelInquiryResponse(qr, qid, rid, found, null, decisionId, VanDecisionOutcome.APPROVED, terms, null, null, q) shouldBe
                compact(message("cancelInquiryResponseFound"))
            VanMessages.cancelInquiryResponse(qr, qid, rid, found, null, decisionId, VanDecisionOutcome.DECLINED, null, "CANCEL_PERIOD_EXPIRED", null, q) shouldBe
                compact(message("cancelInquiryResponseFoundDeclined"))
            VanMessages.cancelInquiryResponse(qr, qid, rid, VanMessages.InquiryResult.PENDING, "vp_01JA0000000000000000000025", null, null, null, null, null, q) shouldBe
                compact(message("cancelInquiryResponsePending"))
            VanMessages.cancelInquiryResponse(qr, qid, rid, VanMessages.InquiryResult.NOT_FOUND, null, null, null, null, null, null, q) shouldBe
                compact(message("cancelInquiryResponseNotFound"))
            VanMessages.cancelInquiryResponse(qr, qid, rid, VanMessages.InquiryResult.NO_EFFECT_CONFIRMED, null, null, null, null, null, "vc_01JA0000000000000000000024", q) shouldBe
                compact(message("cancelInquiryResponseNoEffectConfirmed"))
            VanMessages.cancelInquiryResponse(qr, qid, rid, VanMessages.InquiryResult.UNAVAILABLE, null, null, null, null, null, null, q) shouldBe
                compact(message("cancelInquiryResponseUnavailable"))
        }
        Then("a response whose fields do not follow the outcome is a programming error") {
            shouldThrow<IllegalArgumentException> { VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.APPROVED, decisionId, null, null, null, at) }
            shouldThrow<IllegalArgumentException> { VanMessages.cancelResponse(reply, rid, VanMessages.AuthOutcome.DECLINED, decisionId, null, terms, "EXCEEDS_REMAINING", at) }
        }
    }
})
