package com.paycore.simulator.van.adapter.integration

import com.paycore.simulator.van.domain.VanDecisionOutcome
import com.paycore.simulator.van.domain.VanPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64

/**
 * sim-van-1 프레임·메시지 계약(AUTH-001 §5.2, golden `van-contract-sim-van-1.golden.json` — harness가 두 앱과 독립 생성). 한 번의 read를 프레임 하나로 가정하지 않는다
 * (부분 read·한 read에 프레임 둘). 길이 초과·버전·모르는 키·MAC 불일치·엄격 JSON 위반은 각각 FRAMING/INTEGRITY. payload hash는 전송 필드를 빼고 계산해 golden 값과 같다
 * (rev 0.3 m-7 — 언어가 달라도 같은 canonical).
 */
class VanFrameCodecSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(ClassPathResource("contracts/van-contract-sim-van-1.golden.json").inputStream)
    val keyId = golden.get("hmac").get("keyId").stringValue()
    val key = Base64.getDecoder().decode(golden.get("hmac").get("keyBase64").stringValue())
    val keys = VanKeyRing(mapOf(keyId to VanKeyRing.Key("pg-demo", key)))

    fun hex(name: String): ByteArray = java.util.HexFormat.of().parseHex(golden.get("frames").get(name).stringValue())
    fun message(name: String): JsonNode = golden.get("messages").get(name)
    fun compact(node: JsonNode): ByteArray = mapper.writeValueAsBytes(node)

    /** read 한 번에 [chunk] 바이트까지만 주는 스트림(부분 read 계약). */
    fun trickle(bytes: ByteArray, chunk: Int = 1): InputStream = object : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(len, chunk, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    Given("the golden frames") {
        Then("encoding the golden payload with the test key gives the golden frame bytes") {
            VanFrameCodec.encode(keyId, key, compact(message("authRequest"))) shouldBe hex("authRequest")
            VanFrameCodec.encode(keyId, key, compact(message("authResponseApproved"))) shouldBe hex("authResponseApproved")
        }
        Then("a frame delivered one byte per read is read whole") {
            val frame = VanFrameCodec.read(trickle(hex("authRequest")), keys)
            frame.keyId shouldBe keyId
            frame.clientId shouldBe "pg-demo"
            frame.payload shouldBe compact(message("authRequest"))
        }
        Then("two frames in one read are read one after the other, and the stream end is CLOSED") {
            val input = ByteArrayInputStream(hex("authRequest") + hex("authResponseApproved"))
            VanFrameCodec.read(input, keys).payload shouldBe compact(message("authRequest"))
            VanFrameCodec.read(input, keys).payload shouldBe compact(message("authResponseApproved"))
            shouldThrow<VanFrameException> { VanFrameCodec.read(input, keys) }.failure shouldBe VanFrameFailure.CLOSED
        }
    }

    Given("frames that break the frame contract") {
        Then("a length above 16 KiB, a zero length, a wrong version or a truncated frame is FRAMING") {
            val tooLong = byteArrayOf(0, 0, 0x40, 0x01) + ByteArray(16)
            shouldThrow<VanFrameException> { VanFrameCodec.read(ByteArrayInputStream(tooLong), keys) }.failure shouldBe VanFrameFailure.FRAMING
            shouldThrow<VanFrameException> { VanFrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)), keys) }.failure shouldBe VanFrameFailure.FRAMING
            val wrongVersion = hex("authRequest").also { it[4] = 2 }
            shouldThrow<VanFrameException> { VanFrameCodec.read(ByteArrayInputStream(wrongVersion), keys) }.failure shouldBe VanFrameFailure.FRAMING
            val truncated = hex("authRequest").copyOf(hex("authRequest").size - 3)
            shouldThrow<VanFrameException> { VanFrameCodec.read(ByteArrayInputStream(truncated), keys) }.failure shouldBe VanFrameFailure.FRAMING
        }
        Then("a frame signed with another key or with an unknown key id is INTEGRITY (n-4)") {
            shouldThrow<VanFrameException> { VanFrameCodec.read(ByteArrayInputStream(hex("authResponseApprovedWrongKey")), keys) }.failure shouldBe VanFrameFailure.INTEGRITY
            val unknownKey = VanFrameCodec.encode("sim-van-test-9", key, compact(message("authRequest")))
            shouldThrow<VanFrameException> { VanFrameCodec.read(ByteArrayInputStream(unknownKey), keys) }.failure shouldBe VanFrameFailure.INTEGRITY
        }
    }

    Given("the golden request messages") {
        Then("an AUTH_REQUEST parses into the business request; its payload hash equals the golden canonical hash") {
            val parsed = VanMessages.parseRequest(compact(message("authRequest"))) as VanInbound.Auth
            parsed.messageId shouldBe "01JA0000000000000000000010"
            parsed.request.amountMinor shouldBe 10_000
            parsed.request.plan shouldBe VanPlan.LUMP_SUM
            parsed.request.payloadHash() shouldBe golden.get("payloadHashes").get("authRequest").stringValue()
            val installment = VanMessages.parseRequest(compact(message("authRequestInstallment"))) as VanInbound.Auth
            installment.request.plan shouldBe VanPlan.installment(3)
            installment.request.payloadHash() shouldBe golden.get("payloadHashes").get("authRequestInstallment").stringValue()
        }
        Then("a resent request (other messageId and requestedAt) is the same business request with the same hash (m-7)") {
            val first = VanMessages.parseRequest(compact(message("authRequest"))) as VanInbound.Auth
            val resent = VanMessages.parseRequest(compact(message("authRequestResent"))) as VanInbound.Auth
            resent.messageId shouldBe "01JA0000000000000000000020"
            resent.request shouldBe first.request
            resent.request.payloadHash() shouldBe first.request.payloadHash()
        }
        Then("an INQUIRY_REQUEST parses with its closeIfAbsent flag") {
            val q = VanMessages.parseRequest(compact(message("inquiryRequestClose"))) as VanInbound.Inquiry
            q.inquiryId shouldBe "aq_01JA0000000000000000000002"
            q.originalRequestId shouldBe "ar_01JA0000000000000000000001"
            q.closeIfAbsent shouldBe true
        }
        Then("unknown fields, null, wrong types, missing fields, a bad id or a response type are FRAMING") {
            fun mutated(change: (ObjectNode) -> Unit): ByteArray = compact((message("authRequest").deepCopy() as ObjectNode).also(change))
            listOf(
                mutated { it.put("extra", true) },
                mutated { it.putNull("routeRef") },
                mutated { it.put("amountMinor", "10000") },
                mutated { it.remove("requestedAt") },
                mutated { it.put("requestId", "ar_lowercase") },
                mutated { it.put("amountMinor", 0) },
                mutated { (it.get("plan") as ObjectNode).put("months", 3) },
                mutated { (it.get("authentication") as ObjectNode).put("extra", "x") },
                // INST-CODE-001 IC-8(D-INST-CODE-001): Route·기관 식별 코드는 ^[A-Z0-9]{1,32}$ — 하이픈·밑줄·소문자는 FRAMING.
                mutated { it.put("routeRef", "SIM-VAN-01") },
                mutated { it.put("routeRef", "SIM_VAN_01") },
                mutated { it.put("routeRef", "simvan01") },
                mutated { (it.get("authentication") as ObjectNode).put("acquirerCode", "SIMACQ-A") },
                mutated { (it.get("authentication") as ObjectNode).put("issuerCode", "SIM_ISS_A") },
                mutated { (it.get("authentication") as ObjectNode).put("providerCode", "SIMAUTH-1") },
                mutated { (it.get("authentication") as ObjectNode).put("acquirerCode", "A".repeat(33)) },
                compact(message("authResponseApproved")),
                "not json".toByteArray(),
                ByteArray(0),
            ).forEach { payload ->
                shouldThrow<VanFrameException> { VanMessages.parseRequest(payload) }.failure shouldBe VanFrameFailure.FRAMING
            }
        }
    }

    Given("responses written by the VAN") {
        Then("an APPROVED response is byte-identical to the golden message (field order, compact JSON)") {
            val g = message("authResponseApproved")
            val approval = VanMessages.Approval(
                approvalNumber = "30000001", providerTransactionId = "SIMPTX-0000000001", approvedAmountMinor = 10_000, currency = "KRW", plan = VanPlan.LUMP_SUM,
                cardType = "CREDIT", institutionTransactionDate = java.time.LocalDate.parse("2026-10-16"), institutionBusinessDate = java.time.LocalDate.parse("2026-10-16"),
            )
            VanMessages.authResponse(
                inReplyTo = "01JA0000000000000000000010", requestId = "ar_01JA0000000000000000000001", outcome = VanMessages.AuthOutcome.APPROVED,
                decisionId = "vd_01JA0000000000000000000003", pendingId = null, approval = approval, declineCategory = null,
                respondedAt = java.time.Instant.parse("2026-10-16T01:00:31.120Z"),
            ) shouldBe compact(g)
        }
        Then("the other golden responses are byte-identical too") {
            val at = java.time.Instant.parse("2026-10-16T01:00:31.120Z")
            val rid = "ar_01JA0000000000000000000001"
            val reply = "01JA0000000000000000000010"
            VanMessages.authResponse(reply, rid, VanMessages.AuthOutcome.DECLINED, "vd_01JA0000000000000000000003", null, null, "ISSUER_DECLINED", at) shouldBe
                compact(message("authResponseDeclined"))
            VanMessages.authResponse(reply, rid, VanMessages.AuthOutcome.PENDING, null, "vp_01JA0000000000000000000005", null, null, at) shouldBe compact(message("authResponsePending"))
            VanMessages.authResponse(reply, rid, VanMessages.AuthOutcome.REQUEST_CONFLICT, null, null, null, null, at) shouldBe compact(message("authResponseRequestConflict"))
            VanMessages.authResponse(reply, rid, VanMessages.AuthOutcome.REJECTED_CLOSED, null, null, null, null, at) shouldBe compact(message("authResponseRejectedClosed"))
            val q = java.time.Instant.parse("2026-10-16T01:05:00.000Z")
            val qr = "01JA0000000000000000000011"
            val qid = "aq_01JA0000000000000000000002"
            VanMessages.inquiryResponse(qr, qid, rid, VanMessages.InquiryResult.NOT_FOUND, null, null, null, null, null, null, q) shouldBe compact(message("inquiryResponseNotFound"))
            VanMessages.inquiryResponse(qr, qid, rid, VanMessages.InquiryResult.UNAVAILABLE, null, null, null, null, null, null, q) shouldBe compact(message("inquiryResponseUnavailable"))
            VanMessages.inquiryResponse(qr, qid, rid, VanMessages.InquiryResult.PENDING, "vp_01JA0000000000000000000005", null, null, null, null, null, q) shouldBe
                compact(message("inquiryResponsePending"))
            VanMessages.inquiryResponse(qr, qid, rid, VanMessages.InquiryResult.NO_EFFECT_CONFIRMED, null, null, null, null, null, "vc_01JA0000000000000000000004", q) shouldBe
                compact(message("inquiryResponseNoEffectConfirmed"))
            VanMessages.inquiryResponse(
                qr, qid, rid, VanMessages.InquiryResult.FOUND, null, "vd_01JA0000000000000000000003", VanDecisionOutcome.DECLINED, null, "AUTHENTICATION_INVALID", null, q,
            ) shouldBe compact(message("inquiryResponseFoundDeclined"))
        }
    }
})
