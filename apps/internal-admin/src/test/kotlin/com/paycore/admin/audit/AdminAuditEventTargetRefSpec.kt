package com.paycore.admin.audit

import com.paycore.admin.audit.domain.AdminAuditEvent
import com.paycore.admin.audit.domain.AuditActorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 감사 대상 참조 형식(ACQ-VIEW-001 I-15): 기존 접두사 3자 + ULID(paymentId 등)에 더해 매입 파일 ID(파일 계약 v1)만 받는다 — 둘 다 고정 문법 식별자라 자유 문구가 감사 행에 들어가지 않는다.
 * 계정 대상과 함께 두지 않는다(기존 규칙 그대로).
 */
class AdminAuditEventTargetRefSpec : FunSpec({

    fun event(targetRef: String?, targetActorId: String? = null, targetMerchantId: String? = null) = AdminAuditEvent(
        occurredAt = Instant.parse("2026-11-06T00:00:00Z"), actorKind = AuditActorKind.ADMIN, actorId = "adm_01JC00000000000000000000V1", actorDisplayName = "Viewer",
        executorActorId = null, action = "ACQUISITION_FILE_VIEWED", targetActorId = targetActorId, resultCode = "OK", approvalId = null, correlationId = "corr-1",
        remoteIp = null, loginNameHmac = null, detail = null, targetRef = targetRef, targetMerchantId = targetMerchantId,
    )

    test("a payment id and an acquisition file id are accepted") {
        event("pay_01JC0000000000000000000001")
        event("ACQ.SIMACQA.TEST.20261020.001")
        event("ACQ.${"A".repeat(32)}.LIVE.20261020.999")
    }

    test("MERCHANT-OPS-002 C-6: a four-letter prefix id (site_) is accepted; five letters, upper case, two letters or a short ULID are not") {
        event("site_01JC0000000000000000000001")
        event("biz_01JC0000000000000000000001")
        listOf("sitex_01JC0000000000000000000001", "SITE_01JC0000000000000000000001", "site_01JC000000000000000000001", "si_01JC0000000000000000000001")
            .forEach { ref -> withClue(ref) { shouldThrow<IllegalArgumentException> { event(ref) } } }
    }

    test("free text, near-miss file ids and an account target together are refused") {
        listOf(
            "ACQ.simacqa.TEST.20261020.001", "ACQ.SIMACQA.PROD.20261020.001", "ACQ.SIMACQA.TEST.2026102.001", "ACQ.SIMACQA.TEST.20261020.01",
            "ACQ.SIMACQA.TEST.20261020.001.dat", "ACQ.${"A".repeat(33)}.TEST.20261020.001", "file 1", "x ACQ.SIMACQA.TEST.20261020.001",
        ).forEach { ref -> withClue(ref) { shouldThrow<IllegalArgumentException> { event(ref) } } }
        shouldThrow<IllegalArgumentException> { event("ACQ.SIMACQA.TEST.20261020.001", targetActorId = "adm_01JC00000000000000000000V2") }
    }

    // ------------------------------------------------------------------ MERCHANT-CODE-001 MC-3a(§5.1·§5.6.4 — MC-3-6, C-32·C-36)

    val merchant = "mer_01JCME00000000000000000001"

    test("MC-3-6 TARGET_REF table: a merchant code (§3.1) is a target reference; codes, xxx_<ULID> ids and ACQ file ids never match each other's shape") {
        // 코드는 '_'·'.'가 없고 소문자 시작 6~14자 — 기존 ID(접두사 + '_' + ULID 26자)·ACQ(대문자 + '.')와 겹치지 않는다.
        val code = Regex("^[a-z][a-z0-9]{5,13}$")
        val id = Regex("^[a-z]{3,4}_[0-9A-HJKMNP-TV-Z]{26}$")
        val file = Regex("^ACQ[.][A-Z0-9]{1,32}[.](TEST|LIVE)[.][0-9]{8}[.][0-9]{3}$")
        val samples = listOf("shopabcd12", "a00000", "a0000000000000", merchant, "pay_01JC0000000000000000000001", "site_01JC0000000000000000000001", "ACQ.SIMACQA.TEST.20261020.001")
        samples.map { s -> listOf(code, id, file).count { it.matches(s) } } shouldBe List(samples.size) { 1 }
        samples.map { listOf(code.matches(it), id.matches(it), file.matches(it)) } shouldBe listOf(
            listOf(true, false, false), listOf(true, false, false), listOf(true, false, false), listOf(false, true, false), listOf(false, true, false),
            listOf(false, true, false), listOf(false, false, true),
        )
        event("shopabcd12", targetMerchantId = merchant)
        event("a00000", targetMerchantId = merchant)
        event("a0000000000000", targetMerchantId = merchant)
        listOf("ShopAbcd12", "shop1", "a00000000000000", "1shopabcd", "shop-abcd12", "shop abcd").forEach { ref ->
            withClue(ref) { shouldThrow<IllegalArgumentException> { event(ref, targetMerchantId = merchant) } }
        }
    }

    test("MC-3a target_merchant_id rules (V15 CHECK ①~③ in Domain): mer_ format; needs a target reference that is the same mer_ or a code; a code reference needs the merchant key") {
        event(merchant, targetMerchantId = merchant)
        event("shopabcd12", targetMerchantId = merchant)
        event(merchant)
        event("pay_01JC0000000000000000000001")
        listOf(
            Triple("mer_lowercase00000000000000", "mer_lowercase00000000000000", "format"),
            Triple(merchant, "mer_01JCME00000000000000000002", "different merchant"),
            Triple("pay_01JC0000000000000000000001", merchant, "payment ref with a merchant key"),
            Triple(null, merchant, "merchant key without a reference"),
            Triple("shopabcd12", null, "code without the merchant key"),
        ).forEach { (ref, key, why) -> withClue(why) { shouldThrow<IllegalArgumentException> { event(ref, targetMerchantId = key) } } }
        shouldThrow<IllegalArgumentException> { event(null, targetActorId = "adm_01JC00000000000000000000V2", targetMerchantId = merchant) }
    }
})
