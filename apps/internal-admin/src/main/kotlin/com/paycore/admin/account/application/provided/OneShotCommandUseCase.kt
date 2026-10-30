package com.paycore.admin.account.application.provided

/**
 * 1회성 비웹 명령(§4.9 — H-2, D-ADMIN1-002·010·011·012). 호출자는 `account.adapter.batch` runner뿐이다(명시 argv 모드, 웹 서버·스케줄러 없이).
 * DB가 실행 조건·1회성을 강제한다: 부트스트랩 = 고정 행 PK(`admin_bootstrap(id = 1)`), 복구 = 열린 회차 UNIQUE. 성공·패배·거부 모두 감사(패배·거부는 별도 TX).
 * 코드 원문은 [OneShotResult.Done]에만 — runner가 TTY 또는 0600 새 파일로만 내보낸다(logger·표준 출력 일반 쓰기 금지).
 * 신뢰 경계(n-H): 이 경로의 코드 수령자는 호스트·DB 자격을 가진 운영자이며 시스템 근거(`CredentialBasis.system`)라 관리자 배제 대상이 없다 — 운영 통제(LIVE Gate)로 보호한다.
 */
interface OneShotCommandUseCase {
    fun bootstrap(command: BootstrapCommand): OneShotResult

    fun recover(command: RecoveryCommand): OneShotResult

    /** DB 변경 전 거부(출력 준비 실패 등)도 감사 1행을 남긴다(별도 TX). [reason]은 고정 code. */
    fun recordRefusal(command: OneShotCommand, reason: String, correlationId: String)
}

enum class OneShotCommand { BOOTSTRAP, RECOVERY }

/** 새 관리자 계정 입력(로그인 이름·표시명 — 형식 검사는 Use Case). */
data class NewAdminAccount(val loginName: String, val displayName: String)

/**
 * 부트스트랩(D-ADMIN1-022 — ADMIN-BOOT-002): SECURITY_ADMIN 2([securityAdmin] = SA-A, [secondSecurityAdmin] = SA-B) + APPROVER 1. 세 계정의 login·표시명은 모두 달라야 한다
 * (같은 사람 금지는 운영 절차). SA 둘 다 시스템 근거라, 한 SA가 만든 계정의 `ROLE_GRANT`는 다른 SA가 요청한다(D-ADMIN1-015 유지).
 */
data class BootstrapCommand(val securityAdmin: NewAdminAccount, val secondSecurityAdmin: NewAdminAccount, val approver: NewAdminAccount, val correlationId: String)

/** 복구: 작업 A(기존 계정 [reissueLoginNames]에 복구 코드)와/또는 작업 B(새 SECURITY_ADMIN·APPROVER 쌍 [newPair]). 둘 다 비면 입력 오류. */
data class RecoveryCommand(val reissueLoginNames: List<String>, val newPair: Pair<NewAdminAccount, NewAdminAccount>?, val correlationId: String)

sealed interface OneShotResult {
    /** 성공 — [codes]는 출력할 코드 원문(1회). toString은 원문을 내지 않는다. */
    class Done(val episodeNo: Long?, val codes: List<IssuedCodeLine>) : OneShotResult {
        override fun toString(): String = "Done(episodeNo=$episodeNo, codes=${codes.size})"
    }

    /** 실행 조건 불충족(계정 있음·복구 불필요·열린 회차·대상 없음/부적격 등) — 변경 없음. */
    data class Refused(val reason: String) : OneShotResult

    /** 동시 실행 경합 패배(고정 행 PK·열린 회차 UNIQUE 1062) — 변경 없음. */
    data object Lost : OneShotResult

    /** 1213·1205 — 전체 rollback, 변경 없음. */
    data object RetryLater : OneShotResult

    /** 입력 형식 오류 — TX 없음. */
    data class Invalid(val reason: String) : OneShotResult
}

/** 출력 한 줄: 로그인 이름·목적·코드 원문. toString은 원문을 내지 않는다. */
class IssuedCodeLine(val loginName: String, val purpose: String, val code: String) {
    override fun toString(): String = "IssuedCodeLine(loginName=$loginName, purpose=$purpose, code=***)"
}
