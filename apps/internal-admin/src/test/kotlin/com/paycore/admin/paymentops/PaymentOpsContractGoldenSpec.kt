package com.paycore.admin.paymentops

import com.paycore.admin.paymentops.adapter.integration.AdminCallSigner
import com.paycore.admin.paymentops.domain.OperatorCancelExecutionBody
import com.paycore.admin.paymentops.domain.OwnerResponseClassifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/**
 * ADMIN 쪽 계약 golden 대조(ADMIN-PAYMENT-OPS-001 §4.19 ②·③ — harness가 Python 표준 라이브러리로 두 앱과 독립 생성, PAYMENT 코드 공유 없음).
 * - ② `AdminCallSignerGoldenSpec`: `signVectors` 7개(대상 GET·실행 POST·한글 사유·결정 GET·목록 GET·기한 확인 POST·두 번째 키)의 canonical·헤더 문자열 정확 일치.
 * - ③ 실행 본문: `requestExamples` 3개를 ADMIN 본문 생성기로 만들어 **바이트** 일치(재전송은 이 바이트 그대로 — I-16).
 * - ③ 결과 분류: `responseCases` 27개 각각 기대 ADMIN 상태·무결성 경보 일치(대역 HTTP 재생은 `PaymentOpsResponseMappingGoldenSpec`).
 */
class PaymentOpsContractGoldenSpec : FunSpec({

    val mapper = JsonMapper.builder().build()
    fun golden(name: String): JsonNode = mapper.readTree(PaymentOpsContractGoldenSpec::class.java.getResource("/contracts/$name")!!.readText(Charsets.UTF_8))
    val signature = golden("admin-call-signature-v1.golden.json")
    val api = golden("admin-payment-ops-api-v1.golden.json")
    val keys = signature["keys"].toList().associate { it["kid"].asString() to Base64.getDecoder().decode(it["secretBase64"].asString()) }

    test("② every sign vector: canonical text and header are identical (tag, method, path, t, body SHA-256; HMAC with the Base64-decoded key)") {
        val vectors = signature["signVectors"].toList()
        vectors.size shouldBe 7
        vectors.associate { v ->
            val signer = AdminCallSigner(v["kid"].asString(), keys.getValue(v["kid"].asString()))
            val body = v["body"].asString().toByteArray(Charsets.UTF_8)
            v["name"].asString() to (signer.canonical(v["method"].asString(), v["path"].asString(), v["t"].asLong(), body) to
                signer.header(v["method"].asString(), v["path"].asString(), v["t"].asLong(), body))
        } shouldBe vectors.associate { v -> v["name"].asString() to (v["canonical"].asString() to v["header"].asString()) }
    }

    test("② the execute signature does not verify on the deadline-confirmation path (path is signed)") {
        val v = signature["signVectors"].toList().single { it["name"].asString() == "execute-post" }
        val signer = AdminCallSigner("adm-golden-k1", keys.getValue("adm-golden-k1"))
        val body = v["body"].asString().toByteArray(Charsets.UTF_8)
        (signer.header("POST", "/internal/v1/admin/operator-cancellations/apr_01JC0000000000000000000001/deadline-confirmation", v["t"].asLong(), body) == v["header"].asString()) shouldBe false
    }

    test("③ every request example: the ADMIN execution body generator produces the identical bytes") {
        val examples = api["requestExamples"].toList()
        examples.size shouldBe 3
        examples.associate { e ->
            val b = mapper.readTree(e["body"].asString())
            e["name"].asString() to OperatorCancelExecutionBody(
                b["approvalId"].asString(), b["paymentId"].asString(), b["amountMinor"].asLong(), b["currency"].asString(), b["requestedBy"].asString(),
                b["approvedBy"].asString(), b["reason"].asString(), b["purpose"]["kind"].asString(), b["purpose"]["reference"].asString(),
                b["approvedTargetFingerprint"].asString(), Instant.parse(b["approvedAt"].asString()), Instant.parse(b["executeBefore"].asString()),
                b["correlationId"].asString(),
            ).toBytes().toList()
        } shouldBe examples.associate { e -> e["name"].asString() to e["body"].asString().toByteArray(Charsets.UTF_8).toList() }
    }

    test("③ every response case maps to its expected ADMIN state and integrity alert (D-OPS-003·007, I-21)") {
        val cases = api["responseCases"].toList()
        cases.size shouldBe 27
        cases.associate { c ->
            val body = c["body"]
            val code = body["error"]?.asString()
            val started = body["cancellationId"] != null
            val r = OwnerResponseClassifier.classify(c["httpStatus"].asInt(), code, cancellationIdValid = started)
            val state = when (r.kind) {
                OwnerResponseClassifier.Kind.STARTED -> "EXECUTED"
                OwnerResponseClassifier.Kind.OWNER_REJECTED -> "OWNER_REJECTED"
                OwnerResponseClassifier.Kind.UNKNOWN -> "EXECUTION_UNKNOWN"
            }
            // golden ③ expectedIntegrityAlert = 무결성 경보만(파생 409·400). 401·APPROVAL_WINDOW_INVALID는 운영 경보(operationalAlert — I-97).
            c["name"].asString() to (state to (r.alert || r.integrityFlag != null))
        } shouldBe cases.associate { c -> c["name"].asString() to (c["expectedAdminState"].asString() to c["expectedIntegrityAlert"].asBoolean()) }
    }

    test("I-97: 401 and APPROVAL_WINDOW_INVALID raise an operational alert (not the golden integrity alert)") {
        listOf(OwnerResponseClassifier.classify(401, "UNAUTHENTICATED"), OwnerResponseClassifier.classify(409, "APPROVAL_WINDOW_INVALID"))
            .map { Triple(it.kind, it.alert, it.operationalAlert) } shouldBe listOf(
                Triple(OwnerResponseClassifier.Kind.UNKNOWN, false, true), Triple(OwnerResponseClassifier.Kind.OWNER_REJECTED, false, true),
            )
    }

    test("③ a 200 without a valid cancellation id, unknown 4xx/5xx and redirects are never terminal (UNKNOWN)") {
        listOf(
            OwnerResponseClassifier.classify(200, null, cancellationIdValid = false), OwnerResponseClassifier.classify(413, null),
            OwnerResponseClassifier.classify(415, null), OwnerResponseClassifier.classify(404, "NOT_FOUND"), OwnerResponseClassifier.classify(302, null),
            OwnerResponseClassifier.classify(500, null), OwnerResponseClassifier.classify(409, null), OwnerResponseClassifier.classify(409, "lower"),
        ).map { it.kind }.toSet() shouldBe setOf(OwnerResponseClassifier.Kind.UNKNOWN)
    }

    test("S7 review M-1(c)·L-B: the PAYMENT 400 codes (ended without a PAYMENT decision row) = golden ③ 400 codes ∪ golden ② 400 reasons (a body read failure is 503 now)") {
        val golden3 = (api["strictRejections"].toList() + api["responseCases"].toList()).filter { it["httpStatus"].asInt() == 400 }
            .map { (it["error"] ?: it["body"]["error"]).asString() }
        val golden2 = signature["verifyCases"].toList().map { it["expected"] }.filter { it["httpStatus"]?.asInt() == 400 }.map { it["reason"].asString() }
        golden2.toSet() shouldBe setOf("QUERY_NOT_ALLOWED")
        OwnerResponseClassifier.PAYMENT_BAD_REQUEST_CODES shouldBe (golden3 + golden2).toSet()
        // L-B: PAYMENT의 본문 읽기 실패는 재시도할 수 있는 503 — ADMIN은 결과 불명확(재전송)으로 본다.
        OwnerResponseClassifier.classify(503, "REQUEST_UNREADABLE").kind shouldBe OwnerResponseClassifier.Kind.UNKNOWN
        // 분류기 400 경로와 같은 집합: 모두 OWNER_REJECTED + 무결성 경보(ADMIN 결함 — golden ③).
        OwnerResponseClassifier.PAYMENT_BAD_REQUEST_CODES.map { OwnerResponseClassifier.classify(400, it) }.map { it.kind to it.alert }.toSet() shouldBe
            setOf(OwnerResponseClassifier.Kind.OWNER_REJECTED to true)
    }

    test("execution body escapes JSON specials and keeps millisecond timestamps; it rejects invalid fields") {
        val body = OperatorCancelExecutionBody(
            "apr_01JC0000000000000000000001", "pay_01JC0000000000000000000001", 1, "KRW", "adm_01JC00000000000000000000R1", "adm_01JC00000000000000000000A1",
            "a\"b\\c\td\u0001", "INCIDENT_OR_RECON_CORRECTION", "R-1", "oct1:" + "0".repeat(64), Instant.parse("2026-10-27T01:02:03Z"),
            Instant.parse("2026-10-28T01:02:03.400Z"), "corr-1",
        ).toJson()
        body.contains("\"reason\":\"a\\\"b\\\\c\\td\\u0001\"") shouldBe true
        body.contains("\"approvedAt\":\"2026-10-27T01:02:03.000Z\",\"executeBefore\":\"2026-10-28T01:02:03.400Z\"") shouldBe true
    }
})
