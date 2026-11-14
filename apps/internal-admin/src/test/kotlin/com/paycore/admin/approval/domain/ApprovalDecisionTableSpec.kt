package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * 승인 판정표(§4.7.2 — `ApprovalDecisionTableSpec`, ADMIN-PAYMENT-OPS-001 §4.9·§7.2 "7종 전수"): 상태 × 명령 전수.
 * - ADMIN 소유 6종은 어떤 경로로도 `EXECUTING`·`EXECUTION_UNKNOWN`에 도달하지 않는다(H-1). `APPROVED`는 쓰지 않는다(I-20 — 상태 값 자체가 없다).
 * - 소유 서비스 종류 `OPERATOR_CORRECTION_CANCEL`: PENDING → `beginExecution` → EXECUTING → (`executionUnknown`) → `executed`·`ownerRejected`. EXECUTING 이후 거절·철회·만료·무효
 *   불가(모든 명령 `ALREADY_DECIDED`). 단일 TX 종료 전이(`executed(by…)`·`ownerRejected(by…)`)는 ADMIN 소유 종류만(M-6 fail-closed).
 * 2인 규칙(D-ADMIN1-004): 승인자 ≠ 요청자, 계정 대상이면 승인자 ≠ 대상·요청자 ≠ 대상. 만료는 TX 시각(`expires_at <= now`)으로.
 */
class ApprovalDecisionTableSpec : BehaviorSpec({

    val now = Instant.parse("2026-10-25T01:00:00.000Z")
    val requester = "adm_" + "1".repeat(26)
    val approver = "adm_" + "2".repeat(26)
    val target = "adm_" + "3".repeat(26)
    val adminOwned = ApprovalKind.entries.filter { it.executionModel == ApprovalExecutionModel.ADMIN_OWNED }
    var seq = 0
    fun id() = "apr_" + (++seq).toString().padStart(26, '0')
    fun pending(kind: ApprovalKind) = ApprovalRequest.create(
        id(), kind, target, 7, "{}", if (kind.needsGrantedRole) "VIEWER" else null, if (kind.needsCause) id() else null, requester, "Req", now, Duration.ofHours(24),
    )
    fun ownerPending() = OwnerServiceApprovalFixture.pending(id(), requester, now)

    /** 모든 종료 상태(ADMIN 소유 종류가 도달하는 것). */
    fun terminals(kind: ApprovalKind): List<ApprovalRequest> = listOf(
        pending(kind).executed(approver, "Apr", now), pending(kind).invalidated(approver, "Apr", "TARGET_CHANGED", now),
        pending(kind).ownerRejected(approver, "Apr", "NOT_ELIGIBLE", now), pending(kind).rejected(approver, "Apr", now),
        pending(kind).withdrawn(requester, "Req", now), pending(kind).expireIfDue(now.plus(Duration.ofHours(24)))!!,
    )

    /** 소유 서비스 종류의 모든 비PENDING 상태(EXECUTING·EXECUTION_UNKNOWN 포함). */
    fun ownerNonPending(): List<ApprovalRequest> {
        val executing = { ownerPending().beginExecution(approver, "Apr", now) }
        return listOf(
            executing(), executing().executionUnknown(), executing().executed(), executing().executionUnknown().executed(),
            executing().ownerRejected("CANCEL_IN_PROGRESS"), executing().executionUnknown().ownerRejected("APPROVAL_EXECUTION_EXPIRED"),
            ownerPending().invalidated(approver, "Apr", "TARGET_CHANGED", now), ownerPending().rejected(approver, "Apr", now),
            ownerPending().withdrawn(requester, "Req", now), ownerPending().expireIfDue(now.plus(Duration.ofHours(24)))!!,
        )
    }

    Given("the 7 approval kinds (§4.9)") {
        Then("execution model, requester role and target kind are classified exhaustively; every kind is approved by an APPROVER") {
            ApprovalKind.entries.associateWith { Triple(it.executionModel, it.requesterRole, it.targetKind) } shouldBe mapOf(
                ApprovalKind.ROLE_GRANT to Triple(ApprovalExecutionModel.ADMIN_OWNED, "SECURITY_ADMIN", ApprovalTargetKind.ADMIN_ACCOUNT),
                ApprovalKind.ACCOUNT_UNLOCK to Triple(ApprovalExecutionModel.ADMIN_OWNED, "SECURITY_ADMIN", ApprovalTargetKind.ADMIN_ACCOUNT),
                ApprovalKind.ACCOUNT_REACTIVATE to Triple(ApprovalExecutionModel.ADMIN_OWNED, "SECURITY_ADMIN", ApprovalTargetKind.ADMIN_ACCOUNT),
                ApprovalKind.TOTP_REENROLL to Triple(ApprovalExecutionModel.ADMIN_OWNED, "SECURITY_ADMIN", ApprovalTargetKind.ADMIN_ACCOUNT),
                ApprovalKind.PASSWORD_RESET to Triple(ApprovalExecutionModel.ADMIN_OWNED, "SECURITY_ADMIN", ApprovalTargetKind.ADMIN_ACCOUNT),
                ApprovalKind.ROLE_REACTIVATE to Triple(ApprovalExecutionModel.ADMIN_OWNED, "SECURITY_ADMIN", ApprovalTargetKind.ADMIN_ACCOUNT),
                ApprovalKind.OPERATOR_CORRECTION_CANCEL to Triple(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", ApprovalTargetKind.PAYMENT),
                // ACQ-ADMIN-001 B1-A(D-ACQA-002): 재매입 — 요청 OPERATOR, 대상 원 매입 파일.
                ApprovalKind.ACQ_REACQUISITION to Triple(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", ApprovalTargetKind.ACQUISITION_FILE),
                // 기대값 변경(B2-2 MERCHANT-OPS-002 §9.5 C-11): 가맹점 계약 승인 — 소유 서비스(MASTER), 요청 OPERATOR, 대상 가맹점.
                ApprovalKind.MERCHANT_CONTRACT_APPROVAL to Triple(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", ApprovalTargetKind.MERCHANT),
                // 기대값 변경(B2-3 §9.5): 첫 Default MID 발급 — 같은 실행 모델·요청 역할·대상.
                ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE to Triple(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", ApprovalTargetKind.MERCHANT),
                // 목록 추가(B3-1 MERCHANT-OPS-002 §9.16): 결제수단 개통 승인·Site 심사 승인 — 같은 실행 모델·요청 역할·대상.
                ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION to Triple(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", ApprovalTargetKind.MERCHANT),
                ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL to Triple(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", ApprovalTargetKind.MERCHANT),
                // 목록 추가(B3-2 MERCHANT-OPS-002 §9.16 — D-MOPS-009 ②): 통화 활성화 — 같은 실행 모델·요청 역할·대상.
                ApprovalKind.MERCHANT_CURRENCY_ENABLEMENT to Triple(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", ApprovalTargetKind.MERCHANT),
            )
            ApprovalKind.entries.map { it.approverRole }.toSet() shouldBe setOf("APPROVER")
            ApprovalKind.OPERATOR_CORRECTION_CANCEL.let { listOf(it.needsGrantedRole, it.needsCause, it.escalatesPrivilege, it.issuesEnrollmentCode, it.mayActivatePrivilegedRole(null)) } shouldBe
                List(5) { false }
        }
        Then("I-20: the status set has no APPROVED; the owner-service-only states are exactly EXECUTING and EXECUTION_UNKNOWN") {
            ApprovalStatus.entries.map { it.name }.contains("APPROVED") shouldBe false
            ApprovalStatus.OWNER_SERVICE_ONLY shouldBe setOf(ApprovalStatus.EXECUTING, ApprovalStatus.EXECUTION_UNKNOWN)
        }
    }

    Given("PENDING requests of every ADMIN-owned kind") {
        Then("approve/reject proceed for a different approver before expiry; withdraw proceeds for the requester") {
            adminOwned.forEach { kind ->
                listOf(
                    pending(kind).decideApproval(approver, now), pending(kind).decideRejection(approver, now), pending(kind).decideWithdrawal(requester, now),
                ) shouldBe List(3) { ApprovalDecision.Proceed }
            }
        }
        Then("self-approval, approving one's own target, and withdrawing someone else's request are refused without change") {
            adminOwned.forEach { kind ->
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
            adminOwned.forEach { kind ->
                val at = now.plus(Duration.ofHours(24))
                listOf(pending(kind).decideApproval(approver, at), pending(kind).decideRejection(approver, at), pending(kind).decideWithdrawal(requester, at))
                    .map { it.shouldBeInstanceOf<ApprovalDecision.Expired>().expired.status } shouldBe List(3) { ApprovalStatus.EXPIRED }
                pending(kind).expireIfDue(at.minusMillis(1)) shouldBe null
            }
        }
        Then("M-6: ADMIN-owned kinds never begin an owner-service execution") {
            adminOwned.forEach { kind -> shouldThrow<IllegalStateException> { pending(kind).beginExecution(approver, "Apr", now) } }
        }
    }

    Given("every terminal state × every command") {
        Then("all commands are ALREADY_DECIDED (no change), the expiry job leaves them, and decision methods refuse to run") {
            adminOwned.forEach { kind ->
                terminals(kind).forEach { closed ->
                    listOf(closed.decideApproval(approver, now), closed.decideRejection(approver, now), closed.decideWithdrawal(requester, now)) shouldBe
                        List(3) { ApprovalDecision.AlreadyDecided }
                    closed.expireIfDue(now.plus(Duration.ofDays(2))) shouldBe null
                    shouldThrow<IllegalStateException> { closed.executed(approver, "Apr", now) }
                }
            }
        }
        Then("no path of the 6 ADMIN-owned kinds reaches EXECUTING or EXECUTION_UNKNOWN (H-1), and such rows are not reconstituted") {
            adminOwned.flatMap { terminals(it) }.map { it.status }.toSet().intersect(ApprovalStatus.OWNER_SERVICE_ONLY) shouldBe emptySet()
            ApprovalStatus.OWNER_SERVICE_ONLY.forEach { status ->
                shouldThrow<IllegalArgumentException> {
                    ApprovalRequest.reconstitute(
                        id(), ApprovalKind.ACCOUNT_UNLOCK, ApprovalTarget.AdminAccount(target, 1), "{}", null, null, requester, "R", now, now.plusSeconds(60), status,
                        approver, "A", now, null,
                    )
                }
            }
        }
    }

    Given("a PENDING OPERATOR_CORRECTION_CANCEL request (owner-service, Payment target)") {
        Then("approve/reject proceed for a different approver; withdraw for the requester; the requester cannot approve; expiry wins at expires_at") {
            listOf(
                ownerPending().decideApproval(approver, now), ownerPending().decideRejection(approver, now), ownerPending().decideWithdrawal(requester, now),
                ownerPending().decideApproval(requester, now), ownerPending().decideWithdrawal(approver, now),
            ) shouldBe listOf(
                ApprovalDecision.Proceed, ApprovalDecision.Proceed, ApprovalDecision.Proceed,
                ApprovalDecision.Refused(ApprovalRequest.SELF_APPROVAL), ApprovalDecision.Refused(ApprovalRequest.NOT_REQUESTER),
            )
            val at = now.plus(Duration.ofHours(24))
            listOf(ownerPending().decideApproval(approver, at), ownerPending().decideRejection(approver, at), ownerPending().decideWithdrawal(requester, at))
                .map { it.shouldBeInstanceOf<ApprovalDecision.Expired>().expired.status } shouldBe List(3) { ApprovalStatus.EXPIRED }
            listOf(ownerPending().decideRejection(approver, at.minusMillis(1)), ownerPending().decideWithdrawal(requester, at.minusMillis(1))) shouldBe
                List(2) { ApprovalDecision.Proceed }
        }
        Then("beginExecution moves PENDING to EXECUTING and records the approver as the decider; the approval time stays through the results") {
            val executing = ownerPending().beginExecution(approver, "Apr", now)
            listOf(executing.status, executing.decidedBy, executing.decidedAt, executing.resultCode) shouldBe listOf(ApprovalStatus.EXECUTING, approver, now, null)
            val unknown = executing.executionUnknown()
            listOf(unknown.status, unknown.decidedBy, unknown.decidedAt) shouldBe listOf(ApprovalStatus.EXECUTION_UNKNOWN, approver, now)
            listOf(executing.executed().status, unknown.executed().status) shouldBe listOf(ApprovalStatus.EXECUTED, ApprovalStatus.EXECUTED)
            unknown.ownerRejected("APPROVAL_EXECUTION_EXPIRED").let { listOf(it.status, it.resultCode, it.decidedBy, it.decidedAt) } shouldBe
                listOf(ApprovalStatus.OWNER_REJECTED, "APPROVAL_EXECUTION_EXPIRED", approver, now)
        }
        Then("beginExecution refuses the requester as approver, an expired request and a non-PENDING request") {
            shouldThrow<IllegalArgumentException> { ownerPending().beginExecution(requester, "Req", now) }
            shouldThrow<IllegalArgumentException> { ownerPending().beginExecution(approver, "Apr", now.plus(Duration.ofHours(24))) }
            shouldThrow<IllegalStateException> { ownerPending().beginExecution(approver, "Apr", now).beginExecution(approver, "Apr", now) }
        }
        Then("M-6: the single-TX terminal transitions of the ADMIN-owned path refuse owner-service requests (fail-closed)") {
            shouldThrow<IllegalStateException> { ownerPending().executed(approver, "Apr", now) }
            shouldThrow<IllegalStateException> { ownerPending().ownerRejected(approver, "Apr", "NOT_ELIGIBLE", now) }
            shouldThrow<IllegalStateException> { ownerPending().targetActorId }
            shouldThrow<IllegalStateException> { ownerPending().targetSecurityVersion }
        }
        Then("owner-service result transitions need EXECUTING / EXECUTION_UNKNOWN; executionUnknown needs EXECUTING; ADMIN-owned kinds cannot use them") {
            shouldThrow<IllegalStateException> { ownerPending().executed() }
            shouldThrow<IllegalStateException> { ownerPending().ownerRejected("CANCEL_IN_PROGRESS") }
            shouldThrow<IllegalStateException> { ownerPending().executionUnknown() }
            shouldThrow<IllegalStateException> { ownerPending().beginExecution(approver, "Apr", now).executionUnknown().executionUnknown() }
            shouldThrow<IllegalStateException> { pending(ApprovalKind.ACCOUNT_UNLOCK).executed() }
            shouldThrow<IllegalArgumentException> { ownerPending().beginExecution(approver, "Apr", now).ownerRejected("lower-case") }
        }
    }

    Given("every non-PENDING OPERATOR_CORRECTION_CANCEL state × every command") {
        Then("after EXECUTING nothing can reject, withdraw, expire or invalidate it; terminal states accept no result transition") {
            ownerNonPending().forEach { request ->
                listOf(request.decideApproval(approver, now), request.decideRejection(approver, now), request.decideWithdrawal(requester, now)) shouldBe
                    List(3) { ApprovalDecision.AlreadyDecided }
                request.expireIfDue(now.plus(Duration.ofDays(2))) shouldBe null
                shouldThrow<IllegalStateException> { request.rejected(approver, "Apr", now) }
                shouldThrow<IllegalStateException> { request.withdrawn(requester, "Req", now) }
                shouldThrow<IllegalStateException> { request.invalidated(approver, "Apr", "TARGET_CHANGED", now) }
                shouldThrow<IllegalStateException> { request.beginExecution(approver, "Apr", now) }
                if (request.status.terminal) {
                    shouldThrow<IllegalStateException> { request.executed() }
                    shouldThrow<IllegalStateException> { request.ownerRejected("CANCEL_IN_PROGRESS") }
                }
            }
        }
    }

    Given("S4 review L-3: reconstitution of terminal rows") {
        Then("EXECUTED / OWNER_REJECTED rows need their decider, OWNER_REJECTED needs its result code") {
            val payment = OwnerServiceApprovalFixture.PAYMENT_TARGET
            fun row(status: ApprovalStatus, by: String?, code: String?) = ApprovalRequest.reconstitute(
                id(), ApprovalKind.OPERATOR_CORRECTION_CANCEL, payment, "{}", null, null, requester, "R", now, now.plusSeconds(60), status, by, by, now, code,
            )
            listOf(row(ApprovalStatus.EXECUTED, approver, null).status, row(ApprovalStatus.OWNER_REJECTED, approver, "CANCEL_IN_PROGRESS").status) shouldBe
                listOf(ApprovalStatus.EXECUTED, ApprovalStatus.OWNER_REJECTED)
            shouldThrow<IllegalArgumentException> { row(ApprovalStatus.EXECUTED, null, null) }
            shouldThrow<IllegalArgumentException> { row(ApprovalStatus.OWNER_REJECTED, null, "CANCEL_IN_PROGRESS") }
            shouldThrow<IllegalArgumentException> { row(ApprovalStatus.OWNER_REJECTED, approver, null) }
        }
    }

    Given("creation rules") {
        Then("ROLE_GRANT needs a role, ROLE_REACTIVATE needs a cause, others take neither; a requester cannot target themselves") {
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ROLE_GRANT, target, 1, "{}", null, null, requester, "R", now, Duration.ofHours(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ROLE_REACTIVATE, target, 1, "{}", null, null, requester, "R", now, Duration.ofHours(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ACCOUNT_UNLOCK, target, 1, "{}", "VIEWER", null, requester, "R", now, Duration.ofHours(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalRequest.create(id(), ApprovalKind.ACCOUNT_UNLOCK, requester, 1, "{}", null, null, requester, "R", now, Duration.ofHours(1)) }
        }
        Then("M-6: the account-target factory of the single-TX path refuses the owner-service kind; the target kind must match the approval kind") {
            shouldThrow<IllegalArgumentException> {
                ApprovalRequest.create(id(), ApprovalKind.OPERATOR_CORRECTION_CANCEL, target, 1, "{}", null, null, requester, "R", now, Duration.ofHours(1))
            }
            shouldThrow<IllegalArgumentException> {
                ApprovalRequest.create(id(), ApprovalKind.ACCOUNT_UNLOCK, OwnerServiceApprovalFixture.PAYMENT_TARGET, "{}", null, null, requester, "R", now, Duration.ofHours(1))
            }
        }
        Then("privileged-activation kinds (recovery episode lock) and code-issuing kinds are classified") {
            ApprovalKind.entries.associateWith { it.mayActivatePrivilegedRole(if (it.needsGrantedRole) "APPROVER" else null) } shouldBe mapOf(
                ApprovalKind.ROLE_GRANT to true, ApprovalKind.ACCOUNT_UNLOCK to true, ApprovalKind.ACCOUNT_REACTIVATE to true,
                ApprovalKind.TOTP_REENROLL to false, ApprovalKind.PASSWORD_RESET to false, ApprovalKind.ROLE_REACTIVATE to true,
                ApprovalKind.OPERATOR_CORRECTION_CANCEL to false, ApprovalKind.ACQ_REACQUISITION to false,
                // 기대값 변경(B2-2 §9.5 — mayActivatePrivilegedRole false): 가맹점 계약 승인은 권한 역할을 활성화하지 않는다.
                ApprovalKind.MERCHANT_CONTRACT_APPROVAL to false,
                ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE to false,
                // 목록 추가(B3-1): 개통·Site 심사 승인도 권한 역할을 활성화하지 않는다.
                ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION to false,
                ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL to false,
                // 목록 추가(B3-2): 통화 활성화도 권한 역할을 활성화하지 않는다.
                ApprovalKind.MERCHANT_CURRENCY_ENABLEMENT to false,
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
