package com.paycore.admin.audit

import com.paycore.admin.audit.domain.AdminAuditEvent
import com.paycore.admin.audit.domain.AuditActorKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import java.time.Instant

/**
 * 감사 대상 참조 형식(ACQ-VIEW-001 I-15): 기존 접두사 3자 + ULID(paymentId 등)에 더해 매입 파일 ID(파일 계약 v1)만 받는다 — 둘 다 고정 문법 식별자라 자유 문구가 감사 행에 들어가지 않는다.
 * 계정 대상과 함께 두지 않는다(기존 규칙 그대로).
 */
class AdminAuditEventTargetRefSpec : FunSpec({

    fun event(targetRef: String?, targetActorId: String? = null) = AdminAuditEvent(
        occurredAt = Instant.parse("2026-11-06T00:00:00Z"), actorKind = AuditActorKind.ADMIN, actorId = "adm_01JC00000000000000000000V1", actorDisplayName = "Viewer",
        executorActorId = null, action = "ACQUISITION_FILE_VIEWED", targetActorId = targetActorId, resultCode = "OK", approvalId = null, correlationId = "corr-1",
        remoteIp = null, loginNameHmac = null, detail = null, targetRef = targetRef,
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
})
