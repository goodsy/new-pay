package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * 승인 판정표(§4.7.2 — `ApprovalDecisionTableSpec`): 상태 × 명령 전수, ADMIN 소유 6종은 어떤 경로로도 `APPROVED`·`EXECUTING`·`EXECUTION_UNKNOWN`에 도달하지 않는다(H-1).
 * 2인 규칙(D-ADMIN1-004): 승인자 ≠ 요청자, 승인자 ≠ 대상, 요청자 ≠ 대상. 만료는 TX 시각(`expires_at <= now`)으로.
 */
class ApprovalDecisionTableSpec : BehaviorSpec({

    val now = Instant.parse("2026-10-25T01:00:00.000Z")
    val requester = "adm_" + "1".repeat(26)
    val approver = "adm_" + "2".repeat(26)
    val target = "adm_" + "3".repeat(26)
    var seq = 0
    fun id() = "apr_" + (++seq).toString().padStart(26, '0')
    fun pending(kind: ApprovalKind) = ApprovalRequest.create(
        id(), kind, target, 7, "{}", if (kind.needsGrantedRole) "VIEWER" else null, if (kind.needsCause) id() else null, requester, "Req", now, Duration.ofHours(24),
    )
    /** 모든 종료 상태(ADMIN 소유 종류가 도달하는 것). */
    fun terminals(kind: ApprovalKind): List<ApprovalRequest> = listOf(
        pending(kind).executed(approver, "Apr", now), pending(kind).invalidated(approver, "Apr", "TARGET_CHANGED", now),
        pending(kind).ownerRejected(approver, "Apr", "NOT_ELIGIBLE", now), pending(kind).rejected(approver, "Apr", now),
        pending(kind).withdrawn(requester, "Req", now), pending(kind).expireIfDue(now.plus(Duration.ofHours(24)))!!,
    )

    Given("PENDING requests of every ADMIN-owned kind") {
        Then("approve/reject proceed for a different approver before expiry; withdraw proceeds for the requester") {
            ApprovalKind.entries.forEach { kind ->
                listOf(
                    pending(kind).decideApproval(approver, now), pending(kind).decideRejection(approver, now), pending(kind).decideWithdrawal(requester, now),
                ) shouldBe List(3) { ApprovalDecision.Proceed }
            }
        }
        Then("self-approval, approving one's own target, and withdrawing someone else's request are refused without change") {
            ApprovalKind.entries.forEach { kind ->
                listOf(
                    pending(kind).decideApproval(requester, now), pending(kind).decideApproval(target, now), pending(kind).decideRejection(requester, now),
                    pending(kind).decideWithdrawal(approver, now),
                ) shouldBe listOf(
                    ApprovalDecision.Refused(ApprovalRequest.SELF_APPROVAL), ApprovalDecision.Refused(ApprovalRequest.APPROVER_IS_TARGET),
                    ApprovalDecision.Refused(ApprovalRequest.SELF_APPROVAL), ApprovalDecision.Refused(ApprovalRequest.NOT_REQUESTER),
                )
            }
        }
        Then("at or after expires_at (TX time) every command expires the request instead of deciding it") {
            ApprovalKind.entries.forEach { kind ->
                val at = now.plus(Duration.ofHours(24))
                listOf(pending(kind).decideApproval(approver, at), pending(kind).decideRejection(approver, at), pending(kind).decideWithdrawal(requester, at))
                    .map { it.shouldBeInstanceOf<ApprovalDecision.Expired>().expired.status } shouldBe List(3) { ApprovalStatus.EXPIRED }
                pending(kind).expireIfDue(at.minusMillis(1)) shouldBe null
            }
        }
    }

    Given("every terminal state × every command") {
        Then("all commands are ALREADY_DECIDED (no change), the expiry job leaves them, and decision methods refuse to run") {
            ApprovalKind.entries.forEach { kind ->
                terminals(kind).forEach { closed ->
                    listOf(closed.decideApproval(approver, now), closed.decideRejection(approver, now), closed.decideWithdrawal(requester, now)) shouldBe
                        List(3) { ApprovalDecision.AlreadyDecided }
                    closed.expireIfDue(now.plus(Duration.ofDays(2))) shouldBe null
                    shouldThrow<IllegalStateException> { closed.executed(approver, "Apr", now) }
                }
            }
        }
        Then("no path of the 6 ADMIN-owned kinds reaches APPROVED, EXECUTING or EXECUTION_UNKNOWN (H-1)") {
            ApprovalKind.entries.flatMap { terminals(it) }.map { it.status }.toSet().intersect(ApprovalStatus.OWNER_SERVICE_ONLY) shouldBe emptySet()
            ApprovalStatus.OWNER_SERVICE_ONLY.forEach { status ->
                shouldThrow<IllegalArgumentException> {
                    ApprovalRequest.reconstitute(id(), ApprovalKind.ACCOUNT_UNLOCK, target, 1, "{}", null, null, requester, "R", now, now.plusSeconds(60), status, null, null, now, null)
                }
            }
        }
    }

    Given("creation rules") {
        Then("ROLE_GRANT needs a role, ROLE_REACTIVATE needs a cause, others take neither; a requester cannot target themselves") {
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ROLE_GRANT, target, 1, "{}", null, null, requester, "R", now, Duration.ofHours(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ROLE_REACTIVATE, target, 1, "{}", null, null, requester, "R", now, Duration.ofHours(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ACCOUNT_UNLOCK, target, 1, "{}", "VIEWER", null, requester, "R", now, Duration.ofHours(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ACCOUNT_UNLOCK, requester, 1, "{}", null, null, requester, "R", now, Duration.ofHours(1)) }
        }
        Then("privileged-activation kinds (recovery episode lock) and code-issuing kinds are classified") {
            ApprovalKind.entries.associateWith { it.mayActivatePrivilegedRole(if (it.needsGrantedRole) "APPROVER" else null) } shouldBe mapOf(
                ApprovalKind.ROLE_GRANT to true, ApprovalKind.ACCOUNT_UNLOCK to true, ApprovalKind.ACCOUNT_REACTIVATE to true,
                ApprovalKind.TOTP_REENROLL to false, ApprovalKind.PASSWORD_RESET to false, ApprovalKind.ROLE_REACTIVATE to true,
            )
            ApprovalKind.ROLE_GRANT.mayActivatePrivilegedRole("VIEWER") shouldBe false
            ApprovalKind.entries.filter { it.issuesEnrollmentCode }.toSet() shouldBe setOf(ApprovalKind.TOTP_REENROLL, ApprovalKind.PASSWORD_RESET)
        }
        Then("H-A: a reactivation participant equal to the cause reset's requester or approver conflicts") {
            listOf(
                ApprovalParticipants.reactivationConflict(requester, requester, approver), ApprovalParticipants.reactivationConflict(approver, requester, approver),
                ApprovalParticipants.reactivationConflict(target, requester, approver),
            ) shouldBe listOf(ApprovalParticipants.CAUSE_PARTICIPANT, ApprovalParticipants.CAUSE_PARTICIPANT, null)
        }
    }
})
