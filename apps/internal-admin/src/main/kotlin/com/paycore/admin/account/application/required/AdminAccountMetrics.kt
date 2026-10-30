package com.paycore.admin.account.application.required

/**
 * account 보안 지표 Port(§6 — S8). label은 고정 분류값만(단계·결과·명령) — actorId·login_name·IP·코드는 넣지 않는다(그 값은 감사에만).
 * 호출 시점은 **로컬 TX가 끝난 뒤**(커밋된 결과 — rollback된 시도는 결과 `RETRY_LATER`·`ERROR`로 센다).
 */
interface AdminAccountMetrics {
    /** 로그인 단계 결과 — [step] `PASSWORD`·`TOTP`, [result] `SUCCESS`·`FAILED`·`LOCKED`·`NOT_COUNTED`·`REJECTED`·`UNKNOWN_ACCOUNT`·`RETRY_LATER`·`ERROR`. */
    fun login(step: String, result: String)

    /** 계정이 이번 실패로 LOCKED가 됨 — [step] `PASSWORD`·`TOTP`·`STEP_UP_TOTP`. */
    fun lockout(step: String)

    /** 인증 실패 계수 TX가 끝내 실패해 그 1회가 계수되지 않음(D-ADMIN1-016 — 경보 대상). [failure]는 `DEADLOCK`·`LOCK_TIMEOUT`·`OTHER`. */
    fun authenticationFailureNotRecorded(step: String, failure: String)

    /** 등록 코드 흐름 — [operation] `BEGIN`·`PASSWORD`·`TOTP`·`COMPLETE`, [result] `OK`·[EnrollmentFailure] 이름·`RETRY_LATER`·`ERROR`(D-67 코드 무효는 지표만). */
    fun enrollment(operation: String, result: String)

    /** 1회성 명령 — [command] `BOOTSTRAP`·`RECOVERY`, [result] `DONE`·`REFUSED`·`LOST`·`RETRY_LATER`·`INVALID`·`ERROR`. */
    fun oneShot(command: String, result: String)

    /**
     * 활성 역할 보유자 수 gauge(D-ADMIN1-023) — [role] `SECURITY_ADMIN`·`APPROVER`, [count] = ACTIVE 계정 중 그 역할을 현재 가진 수. 갱신 주기마다 덮어쓴다.
     * 경보 기준(운영 설정 — LIVE Gate): 2 미만.
     */
    fun activeRoleHolders(role: String, count: Int)

    /** 활성 역할 gauge 갱신 결과 counter — [result] `SUCCESS`·`FAILURE`(리뷰 Minor-2: 실패가 이어지면 gauge가 오래된 값으로 남는다 — 경보 대상). */
    fun activeRoleRefresh(result: String)

    /** 활성 역할 gauge 갱신 마지막 성공 시각(경과 gauge의 기준 — 수집 시점에 경과를 계산한다). */
    fun activeRoleRefreshSucceeded(at: java.time.Instant)
}
