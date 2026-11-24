package com.paycore.admin.account.application.provided

/**
 * ADMIN 소유 명령 실행 Port(§4.7.3 — approval orchestrator가 **자기 로컬 TX 안에서** 부른다: 모든 메서드 MANDATORY). account는 approval을 모른다(의존 방향 approval → account).
 * - [lockParticipants]: 요청자·승인자·대상 계정 행을 actor ID **오름차순** `FOR UPDATE`(§4.13). 없는 actor는 결과에 없다.
 * - [consumeStepUpTotp]: 재인증 TOTP 검증·단계 소비(같은 TX — 성공하면 `last_totp_step` 갱신, 실패면 변경 없음: 실패 계수는 호출자가 rollback 뒤
 *   [AuthenticationFailureRecorder]의 별도 TX로 — §4.7.4).
 * - [execute]: 승인 증거(잠근 승인 행 값)로 **다시** 요청자≠승인자≠대상·대상 `security_version` 일치를 확인한 뒤 종류별 계정 변경(상태·역할 이벤트·현재 역할·
 *   security_version·세대·코드 발급). 실패면 [OwnedCommandOutcome.Rejected](승인은 `OWNER_REJECTED`).
 * - [lockOpenRecoveryEpisode]: SECURITY_ADMIN·APPROVER를 활성화할 수 있는 종류는 열린 복구 회차가 있으면 회차 행을 먼저 잠근다(§4.9.2 — 0단계).
 * 1213·1205·version 충돌은 [AdminRetryLaterException](전체 rollback 뒤 409).
 */
interface AdminOwnedCommandExecutor {
    fun lockOpenRecoveryEpisode(): Boolean

    fun lockParticipants(actorIds: Collection<String>): Map<String, ParticipantView>

    /** 잠금 없는 현재값(요청 생성 TX가 승인 행 INSERT 전에 대상 스냅샷·버전을 정할 때 — 잠근 뒤 버전을 다시 비교한다). */
    fun snapshot(actorId: String): ParticipantView?

    fun consumeStepUpTotp(actorId: String, code: String): StepUpResult

    /**
     * 재인증 판정의 단일 진입점(승인·반려·마스킹 해제 — ADMIN-DEMO-LOGIN-001 결정 ③). [context]가 데모 간편 로그인 세션이고 서버 켜짐 조건(DEMO + enabled)이면
     * TOTP 검증을 건너뛰고 [StepUpResult.DEMO_SKIPPED](같은 TX에 감사 1행 — 실패 계수·TOTP 단계 불변), 아니면 [consumeStepUpTotp]와 완전히 같다.
     * 기본 구현은 항상 [consumeStepUpTotp](시험 대역 호환) — 실제 구현은 account application.
     */
    fun verifyStepUp(actorId: String, code: String, context: AdminRequestContext): StepUpResult = consumeStepUpTotp(actorId, code)

    /** [targetActorId]에서 원인 [causeApprovalId]로 정지된 채 남아 있는 역할(역할 이벤트 이력 기준). */
    fun rolesSuspendedBy(targetActorId: String, causeApprovalId: String): Set<String>

    fun execute(evidence: OwnedCommandEvidence): OwnedCommandOutcome
}

/** 계정 현재값(잠근 행 또는 스냅샷). 역할은 enum 이름. */
data class ParticipantView(
    val actorId: String,
    val loginName: String,
    val displayName: String,
    val status: String,
    val activeRoles: Set<String>,
    val suspendedRoles: Set<String>,
    val sessionGeneration: Long,
    val securityVersion: Long,
    /** 현재 자격 발급 근거의 요청자·승인자(D-ADMIN1-014·015) — 이 계정의 권한 상승(`ROLE_GRANT`·`ROLE_REACTIVATE`)에서 배제. */
    val credentialIssuers: Set<String>,
) {
    val isActive: Boolean get() = status == "ACTIVE"

    fun holds(role: String): Boolean = isActive && role in activeRoles
}

/** [DEMO_SKIPPED]: 데모 간편 로그인 세션 재인증 생략(결정 ③) — 호출자에게는 통과(FAILED가 아님). */
enum class StepUpResult { ACCEPTED, FAILED, DEMO_SKIPPED }

/** 실행 증거 — 모두 잠근 승인 행의 값. [kind]는 승인 종류 이름(account의 실행 종류와 같은 이름 6종). */
data class OwnedCommandEvidence(
    val approvalId: String,
    val kind: String,
    val requesterActorId: String,
    val approverActorId: String,
    val targetActorId: String,
    val targetSecurityVersion: Long,
    val grantedRole: String?,
    val causeApprovalId: String?,
)

sealed interface OwnedCommandOutcome {
    /** 실행됨. [enrollmentCode]는 재설정 종류만 — 원문 1회(승인 HTTP 응답에서 실행자에게만, §4.7.7). */
    data class Executed(val enrollmentCode: String? = null) : OwnedCommandOutcome {
        override fun toString(): String = "Executed(enrollmentCode=${if (enrollmentCode == null) "none" else "***"})"
    }

    data class Rejected(val code: String) : OwnedCommandOutcome
}
