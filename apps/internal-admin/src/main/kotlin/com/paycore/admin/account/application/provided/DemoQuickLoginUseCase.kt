package com.paycore.admin.account.application.provided

import java.time.Duration
import java.time.Instant

/**
 * 데모 간편 로그인(ADMIN-DEMO-LOGIN-001 — D-ADMIN-AUTH-DEMO-001, **Demo 전용 임시 기능 — 운영 준비 전 반드시 끔**). 켜짐 판정은 설정 Port(DEMO 그리고 enabled).
 * - [options]: 허용 목록 계정 중 DB에 있는 것(표시명·현재 활성 역할·사용 가능 여부). 비잠금 읽기.
 * - [login]: 로컬 TX 1회 — 허용 목록 정확 일치 → 계정 행 `FOR UPDATE` → ACTIVE·실패 한도 미도달 판정 → **세대 +1(새 로그인 우선)** → 감사 1행.
 *   비밀값을 확인하지 않으므로 실패 계수·TOTP 단계는 바꾸지 않는다(§4 C-5). 거부는 원인 비구분 [DemoQuickLoginResult.Rejected].
 */
interface DemoQuickLoginUseCase {
    /** 서버 켜짐 조건(DEMO + enabled). 결정 ③의 재인증 생략·`/api/auth/me` 표시는 이것과 세션 표시가 둘 다일 때만. */
    fun isActive(): Boolean

    fun options(): List<DemoQuickLoginOption>

    fun login(loginName: String, context: AdminRequestContext): DemoQuickLoginResult
}

/** 화면 버튼 하나(비밀 없음). */
data class DemoQuickLoginOption(val loginName: String, val displayName: String, val roles: List<String>, val available: Boolean)

sealed interface DemoQuickLoginResult {
    /** 로그인 완료 — 웹 adapter는 일반 TOTP 성공과 같은 세션 발급 경로로 기록한다. */
    data class Authenticated(
        val actorId: String,
        val sessionGeneration: Long,
        val securityVersion: Long,
        val authenticatedAt: Instant,
        val idle: Duration,
    ) : DemoQuickLoginResult

    data object Rejected : DemoQuickLoginResult
}
