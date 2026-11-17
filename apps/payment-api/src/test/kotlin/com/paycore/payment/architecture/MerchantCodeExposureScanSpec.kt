package com.paycore.payment.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * MERCHANT-CODE-001 §6·§8 MC-4-3(소스 검사 — 실행 시험은 SchemaRolloutOrderV4Spec·CreatePaymentApiSpec·MerchantWebhookBodyJsonSpec): 가맹점 코드는 **가맹점 출력 전용**이다.
 * - Simulator/REAL 기관 전송 0건: 기관 adapter(`payment.adapter.integration.institution` 패키지)와 Simulator 앱 main에 가맹점 코드가 없다(기관 전문·인증 요청에 싣지 않는다).
 * - 결제 Domain·멱등 지문은 코드를 모른다(금액·상태·멱등 키·주문 키 의미 불변 — Financial Consistency).
 * 작업 디렉터리는 모듈 디렉터리(OutboxLockReadScanSpec 선례). 검사 대상이 비어 있으면 실패한다(0건 통과 금지).
 */
class MerchantCodeExposureScanSpec : FunSpec({

    val tokens = listOf("merchantCode", "merchant_code", "MerchantCode")

    fun hits(root: File): Pair<Int, List<String>> {
        val files = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        return files.size to files.filter { f -> tokens.any { f.readText().contains(it) } }.map { it.path }
    }

    test("MC-4-3: no institution adapter (Simulator or REAL route) carries the merchant code") {
        val (scanned, found) = hits(File("src/main/kotlin/com/paycore/payment/payment/adapter/integration/institution"))
        scanned shouldBeGreaterThan 5
        found.shouldBeEmpty()
    }

    test("MC-4-3: the institution simulator app never sees a merchant code") {
        val (scanned, found) = hits(File("../institution-simulator/src/main"))
        scanned shouldBeGreaterThan 10
        found.shouldBeEmpty()
    }

    test("MC-4-4 (Financial Consistency): the payment domain and the request fingerprints do not know the merchant code") {
        val (scanned, found) = hits(File("src/main/kotlin/com/paycore/payment/payment/domain"))
        scanned shouldBeGreaterThan 10
        found.shouldBeEmpty()
        val fingerprints = File("src/main/kotlin/com/paycore/payment/payment/application").walkTopDown().filter { it.isFile && it.name.contains("Fingerprint") }.toList()
        fingerprints.size shouldBeGreaterThan 0
        fingerprints.filter { f -> tokens.any { f.readText().contains(it) } }.shouldBeEmpty()
    }
})
