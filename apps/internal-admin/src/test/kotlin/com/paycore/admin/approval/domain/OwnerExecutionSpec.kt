package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.time.Instant

/**
 * 소유 서비스 실행 행(§4.9 `OwnerExecution`, §4.11 `admin_owner_execution`, §4.16 TX2 갱신 규칙 rev 0.3 L-e).
 * - 불변: 저장 본문 바이트·지문·approvedAt·executeBefore·실행자(재전송은 같은 본문 — 재생성 없음).
 * - `attempt_count`는 보낸 명령 수: 승인 TX1이 1, 회복 선점만 +1, TX2는 바꾸지 않는다.
 * - TX2: `last_response_at` 기록, UNKNOWN이면 `last_failure`·`next_attempt_at = max(현재, 재시도 시각)`(앞으로만), 종료면 `next_attempt_at`·`last_failure` 유지.
 * - 무결성 플래그·재전송 중단은 한 번 서면 지워지지 않는다(D-OPS-006·007, I-26).
 */
class OwnerExecutionSpec : BehaviorSpec({

    val approvedAt = Instant.parse("2026-10-25T01:00:00.000Z")
    val approver = "adm_" + "2".repeat(26)
    val approvalId = "apr_" + "0".repeat(25) + "1"
    fun started() = OwnerServiceApprovalFixture.execution(approvalId, approver, approvedAt)

    Given("an execution row started by the approval TX1") {
        Then("it has one sent command, the stored body and the approval facts; nothing has answered yet") {
            val e = started()
            listOf(e.attemptCount, e.lastAttemptAt, e.nextAttemptAt, e.lastResponseAt, e.lastFailure, e.integrityFlag, e.resendHaltedReason, e.ownerResultCode, e.ownerReference) shouldBe
                listOf(1, approvedAt, approvedAt.plusSeconds(90), null, null, null, null, null, null)
            listOf(e.fingerprint, e.approvedAt, e.executeBefore, e.executorActorId) shouldBe
                listOf(OwnerServiceApprovalFixture.FINGERPRINT, approvedAt, approvedAt.plus(OwnerServiceApprovalFixture.EXECUTION_WINDOW), approver)
            e.requestBody().toList() shouldBe "{\"approvalId\":\"$approvalId\"}".toByteArray().toList()
        }
        Then("the stored body cannot be changed from outside (defensive copies) and is not printed") {
            val body = "{\"reason\":\"secret reason\"}".toByteArray()
            val e = OwnerServiceApprovalFixture.execution(approvalId, approver, approvedAt, body)
            body[0] = 'X'.code.toByte()
            e.requestBody()[1] = 'Y'.code.toByte()
            e.requestBody().toList() shouldBe "{\"reason\":\"secret reason\"}".toByteArray().toList()
            e.toString() shouldNotContain "secret"
        }
        Then("invalid facts are refused: fingerprint format, deadline not after approval, empty or over-limit body, approval id format") {
            fun start(fingerprint: String = OwnerServiceApprovalFixture.FINGERPRINT, before: Instant = approvedAt.plusSeconds(60), body: ByteArray = byteArrayOf(1), id: String = approvalId) =
                OwnerExecution.start(id, body, fingerprint, approvedAt, before, approver, approvedAt)
            listOf(
                { start(fingerprint = "oct1:" + "A".repeat(64)) }, { start(fingerprint = "oct2:" + "a".repeat(64)) }, { start(fingerprint = "oct1:" + "a".repeat(63)) },
                { start(before = approvedAt) }, { start(body = ByteArray(0)) }, { start(body = ByteArray(OwnerExecution.MAX_BODY_BYTES + 1)) }, { start(id = "apr_x") },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
            start(body = ByteArray(OwnerExecution.MAX_BODY_BYTES)).requestBody().size shouldBe OwnerExecution.MAX_BODY_BYTES
        }
    }

    Given("the recovery claim (§4.16 — the only place that counts a resend)") {
        Then("claim adds one attempt, records the attempt time and moves the next attempt to now + backoff") {
            val at = approvedAt.plusSeconds(120)
            val claimed = started().claimed(at, Duration.ofSeconds(60))
            listOf(claimed.attemptCount, claimed.lastAttemptAt, claimed.nextAttemptAt) shouldBe listOf(2, at, at.plusSeconds(60))
            claimed.requestBody().toList() shouldBe started().requestBody().toList()
        }
    }

    Given("the TX2 result (web thread or recovery — L-e)") {
        Then("UNKNOWN: last response and failure are recorded, attempt count unchanged, next attempt only moves forward") {
            val at = approvedAt.plusSeconds(10)
            val later = started().unknownResponse(at, "TIMEOUT", at.plusSeconds(300))
            listOf(later.attemptCount, later.lastResponseAt, later.lastFailure, later.nextAttemptAt) shouldBe listOf(1, at, "TIMEOUT", at.plusSeconds(300))
            // 웹 TX2(재시도 시각이 job 예정보다 이름)는 job의 예정 시각을 당기지 않는다.
            val earlier = later.unknownResponse(at.plusSeconds(1), "CONNECT_FAILED", at.plusSeconds(31))
            listOf(earlier.nextAttemptAt, earlier.lastFailure, earlier.lastResponseAt) shouldBe listOf(at.plusSeconds(300), "CONNECT_FAILED", at.plusSeconds(1))
        }
        Then("a terminal owner result keeps next attempt and the last failure, records the owner code or reference once") {
            val at = approvedAt.plusSeconds(10)
            val unknown = started().unknownResponse(at, "TIMEOUT", at.plusSeconds(30))
            val executed = unknown.ownerResult(at.plusSeconds(5), code = null, reference = "pcn_01JC0000000000000000000001")
            // TX1의 다음 시도(승인 + 90초)가 재시도 시각(at + 30초)보다 늦으므로 UNKNOWN 반영도 당기지 않았고, 종료 반영은 그 값을 그대로 둔다.
            listOf(executed.ownerReference, executed.ownerResultCode, executed.lastFailure, executed.nextAttemptAt, executed.lastResponseAt, executed.attemptCount) shouldBe
                listOf("pcn_01JC0000000000000000000001", null, "TIMEOUT", approvedAt.plusSeconds(90), at.plusSeconds(5), 1)
            val rejected = started().ownerResult(at, code = "CANCEL_IN_PROGRESS", reference = null)
            listOf(rejected.ownerResultCode, rejected.ownerReference) shouldBe listOf("CANCEL_IN_PROGRESS", null)
            shouldThrow<IllegalStateException> { executed.ownerResult(at, code = "CANCEL_IN_PROGRESS", reference = null) }
            shouldThrow<IllegalArgumentException> { started().ownerResult(at, code = null, reference = null) }
            shouldThrow<IllegalArgumentException> { started().ownerResult(at, code = "X", reference = "pcn_01JC0000000000000000000001") }
            shouldThrow<IllegalArgumentException> { started().ownerResult(at, code = null, reference = "cancel-1") }
            shouldThrow<IllegalArgumentException> { started().unknownResponse(at, "lower", at) }
        }
    }

    Given("integrity flag and resend halt (D-OPS-006·007)") {
        Then("each is set once and never cleared or replaced") {
            val flagged = started().flagIntegrity("APPROVAL_CONFLICT").flagIntegrity("DECISION_CONTENT_MISMATCH")
            flagged.integrityFlag shouldBe "APPROVAL_CONFLICT"
            val halted = started().haltResend("REQUESTER_NOT_ELIGIBLE").haltResend("APPROVER_NOT_ELIGIBLE")
            halted.resendHaltedReason shouldBe "REQUESTER_NOT_ELIGIBLE"
            val at = approvedAt.plusSeconds(5)
            flagged.unknownResponse(at, "TIMEOUT", at).ownerResult(at, "CANCEL_IN_PROGRESS", null).integrityFlag shouldBe "APPROVAL_CONFLICT"
            halted.claimed(at, Duration.ofSeconds(30)).resendHaltedReason shouldBe "REQUESTER_NOT_ELIGIBLE"
        }
    }

    Given("reconstitution and identity") {
        Then("a stored row round-trips; equality is by approval id only") {
            val e = started().unknownResponse(approvedAt.plusSeconds(5), "TIMEOUT", approvedAt.plusSeconds(60)).flagIntegrity("APPROVAL_CONFLICT")
            val again = OwnerExecution.reconstitute(
                e.approvalId, e.requestBody(), e.fingerprint, e.approvedAt, e.executeBefore, e.executorActorId, e.attemptCount, e.nextAttemptAt, e.lastAttemptAt,
                e.lastResponseAt, e.lastFailure, e.integrityFlag, e.resendHaltedReason, e.ownerResultCode, e.ownerReference,
            )
            again shouldBe e
            // 다음 시도는 앞으로만: TX1 값(승인 + 90초)이 재시도 시각(승인 + 60초)보다 늦어 그대로다.
            listOf(again.lastFailure, again.integrityFlag, again.nextAttemptAt) shouldBe listOf("TIMEOUT", "APPROVAL_CONFLICT", approvedAt.plusSeconds(90))
            shouldThrow<IllegalArgumentException> {
                OwnerExecution.reconstitute(e.approvalId, e.requestBody(), e.fingerprint, e.approvedAt, e.executeBefore, e.executorActorId, 0, e.nextAttemptAt, null, null, null, null, null, null, null)
            }
        }
    }
})
