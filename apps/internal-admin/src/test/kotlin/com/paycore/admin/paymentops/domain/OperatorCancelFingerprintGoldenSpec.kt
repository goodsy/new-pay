package com.paycore.admin.paymentops.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * `oct1:` 승인 대상 지문 golden 대조(ADMIN-PAYMENT-OPS-001 §4.19 ①, S4). golden은 harness(`test_operator_cancel_target_golden.py`)가 Python 표준 라이브러리로 두 앱과
 * 독립 생성한다. ADMIN 구현(`OperatorCancelTargetFingerprint`)은 PAYMENT 코드를 공유하지 않는다(이 앱 classpath에 PAYMENT 클래스가 없다 — ArchitectureSpec).
 * 벡터마다 canonical **바이트**와 지문 문자열을 정확히 비교한다. 요소 순서는 golden의 `elements` 목록과 같아야 한다.
 */
class OperatorCancelFingerprintGoldenSpec : FunSpec({

    val golden: JsonNode = JsonMapper.builder().build().readTree(
        OperatorCancelFingerprintGoldenSpec::class.java.getResource("/contracts/operator-cancel-target-oct1.golden.json")!!.readText(Charsets.UTF_8),
    )
    fun input(v: JsonNode) = OperatorCancelTargetFingerprint.Input(
        paymentId = v["paymentId"].asString(), amountMinor = v["amountMinor"].asLong(), currency = v["currency"].asString(),
        balanceRevision = v["balanceRevision"].asLong(), approvalId = v["approvalId"].asString(), requestedBy = v["requestedBy"].asString(),
        approvedBy = v["approvedBy"].asString(), purposeKind = v["purposeKind"].asString(), purposeReference = v["purposeReference"].asString(),
    )
    val vectors = golden["vectors"].toList()

    test("the golden declares the 11 elements in the implemented order, tag and version") {
        // Jackson 3 JsonNode의 `map`은 노드 자체를 받는다 — 원소 목록은 toList()로 펼친다.
        listOf(golden["elements"].toList().map { it.asString() },golden["domainTag"].asString(), golden["version"].asString()) shouldBe
            listOf(OperatorCancelTargetFingerprint.ELEMENTS, OperatorCancelTargetFingerprint.DOMAIN_TAG, OperatorCancelTargetFingerprint.VERSION)
    }

    test("all 9 golden vectors: canonical UTF-8 bytes are identical") {
        vectors.size shouldBe 9
        vectors.associate { it["name"].asString() to OperatorCancelTargetFingerprint.canonical(input(it)).toList() } shouldBe
            vectors.associate { it["name"].asString() to it["canonical"].asString().toByteArray(Charsets.UTF_8).toList() }
    }

    test("all 9 golden vectors: the fingerprint string is identical") {
        vectors.associate { it["name"].asString() to OperatorCancelTargetFingerprint.of(input(it)) } shouldBe
            vectors.associate { it["name"].asString() to it["fingerprint"].asString() }
    }

    test("vectors that differ in one element give different fingerprints (no element is ignored)") {
        vectors.map { OperatorCancelTargetFingerprint.of(input(it)) }.toSet().size shouldBe 9
    }

    test("UTF-16 is validated before hashing: a lone surrogate in any text element is refused (no silent '?' replacement)") {
        val base = input(vectors.first())
        listOf(
            base.copy(purposeReference = "RECON-\uD800"), base.copy(purposeReference = "\uDC00RECON"), base.copy(paymentId = "pay_\uD83D"),
            base.copy(requestedBy = "adm_\uDFFF"), base.copy(currency = "K\uD800W"),
        ).forEach { shouldThrow<IllegalArgumentException> { OperatorCancelTargetFingerprint.of(it) } }
        // 짝이 맞는 보조 평면 문자(U+20000)는 허용 — 길이 접두는 UTF-8 4바이트(golden hangul-and-supplementary-reference가 값을 고정).
        OperatorCancelTargetFingerprint.canonical(base.copy(purposeReference = String(Character.toChars(0x20000)))).toString(Charsets.UTF_8)
            .endsWith("4:" + String(Character.toChars(0x20000))) shouldBe true
    }

    test("invalid inputs are refused: amount ≤ 0, negative revision, unknown purpose kind, empty element") {
        val base = input(vectors.first())
        listOf(
            base.copy(amountMinor = 0), base.copy(balanceRevision = -1), base.copy(purposeKind = "OTHER"), base.copy(purposeReference = ""), base.copy(approvalId = ""),
        ).forEach { shouldThrow<IllegalArgumentException> { OperatorCancelTargetFingerprint.of(it) } }
    }
})
