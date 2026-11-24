package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.account.application.provided.DemoQuickLoginUseCase
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component

/**
 * `/api/auth/me` 본문(계정 현재값 — 역할은 세션이 아니라 현재 행). 일반 로그인(TOTP 성공·`GET /api/auth/me`)과 데모 간편 로그인 성공이 같은 본문을 쓴다.
 * ADMIN-DEMO-LOGIN-001 결정 ③ 표시 규칙은 여기 한 곳: [session]의 로그인 방식 표시가 DEMO_QUICK **그리고** 서버 켜짐이면 `"demoQuickLogin":true`, 아니면 필드 없음.
 * 계정이 없으면 null(응답 모양은 호출자가 정한다).
 */
@Component
class AdminMeBody(private val sessions: AdminSessionQuery, private val demoQuickLogin: DemoQuickLoginUseCase) {

    fun of(actorId: String, session: HttpSession?): Map<String, Any>? {
        val view = sessions.current(actorId) ?: return null
        val body = linkedMapOf<String, Any>("actorId" to view.actorId, "displayName" to view.displayName, "roles" to view.roles.sorted())
        val marked = session?.let { s -> AdminSessionState.read(s::getAttribute) }?.isDemoQuick == true
        if (marked && demoQuickLogin.isActive()) body["demoQuickLogin"] = true
        return body
    }
}
