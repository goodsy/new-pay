package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

/**
 * 운영자 보정 취소 요청 고정 값(§4.9 `OperatorCancelRequestSnapshot`, §4.11 `admin_operator_cancel_command` INSERT-only — `pinned_target` 권위).
 * 금액 > 0, 통화 3자리 대문자, 목적 종류 2종, 근거 1~200·사유 1~500(UTF-16 단위 — 세부 문자 규칙은 요청 검사 S5 §4.18), 고정 대상의 Payment = 명령 Payment.
 * 고정 대상 JSON은 결정적(같은 값 = 같은 바이트) — 승인 행 `target_snapshot`은 같은 JSON의 표시용 사본이다.
 */
class OperatorCancelRequestSnapshotSpec : BehaviorSpec({

    val at = Instant.parse("2026-10-25T01:00:00.000Z")
    val approvalId = "apr_" + "0".repeat(25) + "1"

    Given("a request snapshot") {
        Then("it keeps the request facts and the pinned target") {
            val s = OwnerServiceApprovalFixture.command(approvalId, at = at)
            listOf(s.approvalId, s.paymentId, s.amountMinor, s.currency, s.purposeKind, s.purposeReference, s.reason, s.pinnedTarget, s.createdAt) shouldBe listOf(
                approvalId, OwnerServiceApprovalFixture.PAYMENT_ID, 7_000L, "KRW", OperatorCancelPurposeKind.INCIDENT_OR_RECON_CORRECTION, "RECON-2026-0001",
                "duplicate charge correction", OwnerServiceApprovalFixture.pinned(), at,
            )
            s.toString() shouldNotContain "duplicate charge correction"
            s.toString() shouldNotContain "RECON-2026-0001"
        }
        Then("invalid facts are refused") {
            fun of(amount: Long = 1, currency: String = "KRW", ref: String = "R", reason: String = "r", pinnedPayment: String = OwnerServiceApprovalFixture.PAYMENT_ID) =
                OperatorCancelRequestSnapshot.of(
                    approvalId, OwnerServiceApprovalFixture.PAYMENT_ID, amount, currency, OperatorCancelPurposeKind.MERCHANT_TERMINATED_OBLIGATION, ref, reason,
                    OwnerServiceApprovalFixture.pinned(pinnedPayment), at,
                )
            of().amountMinor shouldBe 1L
            of(ref = "가".repeat(200), reason = "나".repeat(500)).reason.length shouldBe 500
            listOf(
                { of(amount = 0) }, { of(amount = -1) }, { of(currency = "krw") }, { of(currency = "KRWX") }, { of(ref = "") }, { of(ref = "가".repeat(201)) },
                { of(reason = "") }, { of(reason = "x".repeat(501)) }, { of(pinnedPayment = OwnerServiceApprovalFixture.paymentId(99)) },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        }
    }

    Given("the pinned target (the authoritative value compared by the approval TX1)") {
        Then("its JSON is deterministic and holds the fixed fields only") {
            OwnerServiceApprovalFixture.pinned().toSnapshotJson() shouldBe
                "{\"paymentId\":\"pay_01JC0000000000000000000001\",\"paymentStatus\":\"AUTHORIZED\",\"currency\":\"KRW\",\"authorizedMinor\":10000," +
                "\"cancellableMinor\":10000,\"balanceRevision\":3,\"onHold\":false}"
            PinnedPaymentTarget(OwnerServiceApprovalFixture.PAYMENT_ID, "AUTHORIZED", null, null, 0, 0, true).toSnapshotJson() shouldBe
                "{\"paymentId\":\"pay_01JC0000000000000000000001\",\"paymentStatus\":\"AUTHORIZED\",\"currency\":null,\"authorizedMinor\":null," +
                "\"cancellableMinor\":0,\"balanceRevision\":0,\"onHold\":true}"
        }
        Then("invalid pinned values are refused (status format, negative amounts or revision, currency format)") {
            listOf(
                { PinnedPaymentTarget(OwnerServiceApprovalFixture.PAYMENT_ID, "authorized\"", "KRW", 1, 1, 1, false) },
                { PinnedPaymentTarget(OwnerServiceApprovalFixture.PAYMENT_ID, "AUTHORIZED", "KR", 1, 1, 1, false) },
                { PinnedPaymentTarget(OwnerServiceApprovalFixture.PAYMENT_ID, "AUTHORIZED", "KRW", -1, 1, 1, false) },
                { PinnedPaymentTarget(OwnerServiceApprovalFixture.PAYMENT_ID, "AUTHORIZED", "KRW", 1, -1, 1, false) },
                { PinnedPaymentTarget(OwnerServiceApprovalFixture.PAYMENT_ID, "AUTHORIZED", "KRW", 1, 1, -1, false) },
                { PinnedPaymentTarget("pay_x", "AUTHORIZED", "KRW", 1, 1, 1, false) },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        }
    }
})
