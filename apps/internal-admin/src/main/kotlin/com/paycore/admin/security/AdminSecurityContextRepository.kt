package com.paycore.admin.security

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionState
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.DeferredSecurityContext
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import java.time.Clock

/**
 * 요청마다 세션의 **문자열 로그인 상태**와 계정 **현재 행**(PK 단건)으로 `SecurityContext`를 만든다(D-08 — `SecurityContext`를 세션에 저장하지 않는다, §17.5).
 * 유효 조건: 단계 AUTHENTICATED, 계정 ACTIVE, 세션의 세대 = 계정 세대(새 로그인 우선·잠금·정지·역할 회수·재설정은 세대 +1 — D-ADMIN1-001), 세션의 security_version =
 * 계정 security_version(세대 없이 security_version만 오르는 사건도 세션을 끝낸다 — S4 리뷰 nit), 절대 한도 안.
 * 무효면 세션을 폐기하고 사유를 요청 속성에 남긴다(401 본문: 세대 불일치 `SESSION_REPLACED`, 만료·손상·한도 초과 `SESSION_EXPIRED` — [JsonErrorResponses]).
 * 권한(authority)은 계정 현재 활성 역할(`ROLE_<역할>`)이며 세션에는 역할이 없다.
 */
class AdminSecurityContextRepository(private val sessions: AdminSessionQuery, private val clock: Clock = Clock.systemUTC()) : SecurityContextRepository {

    override fun loadDeferredContext(request: HttpServletRequest): DeferredSecurityContext {
        val computed by lazy { load(request) }
        return object : DeferredSecurityContext {
            override fun get(): SecurityContext = computed ?: SecurityContextHolder.createEmptyContext()

            override fun isGenerated(): Boolean = computed == null
        }
    }

    /** Security 7에서도 추상 메서드로 남은 옛 API(사용하지 않음 — loadDeferredContext가 쓰인다). */
    @Deprecated("Spring Security uses loadDeferredContext")
    @Suppress("DEPRECATION")
    override fun loadContext(requestResponseHolder: org.springframework.security.web.context.HttpRequestResponseHolder): SecurityContext =
        load(requestResponseHolder.request) ?: SecurityContextHolder.createEmptyContext()

    /** 저장하지 않는다 — 로그인 상태는 웹 adapter가 세션 문자열 속성으로만 기록한다. */
    override fun saveContext(context: SecurityContext, request: HttpServletRequest, response: HttpServletResponse) = Unit

    override fun containsContext(request: HttpServletRequest): Boolean =
        AdminSessionState.read { request.getSession(false)?.getAttribute(it) }?.stage == AdminSessionState.Stage.AUTHENTICATED

    private fun load(request: HttpServletRequest): SecurityContext? {
        val session = request.getSession(false)
        if (session == null) {
            // 요청이 세션 쿠키를 보냈는데 세션이 없음 = 만료(idle)·폐기된 세션.
            if (request.requestedSessionId != null && request.requestURI.startsWith("/api/")) request.setAttribute(FAILURE_ATTRIBUTE, SESSION_EXPIRED)
            return null
        }
        if (AdminSessionState.isCorrupt(session::getAttribute)) return invalidate(request, SESSION_EXPIRED)
        val state = AdminSessionState.read(session::getAttribute) ?: return null
        if (state.stage != AdminSessionState.Stage.AUTHENTICATED) return null
        val view = sessions.current(state.actorId) ?: return invalidate(request, SESSION_EXPIRED)
        if (!clock.instant().isBefore(state.authenticatedAt!!.plus(sessions.policy().absolute))) return invalidate(request, SESSION_EXPIRED)
        if (!view.isActive || view.sessionGeneration != state.sessionGeneration || view.securityVersion != state.securityVersion) return invalidate(request, SESSION_REPLACED)
        val authorities = view.roles.map { SimpleGrantedAuthority("ROLE_$it") }
        return SecurityContextHolder.createEmptyContext().apply {
            authentication = UsernamePasswordAuthenticationToken.authenticated(view.actorId, null, authorities)
        }
    }

    private fun invalidate(request: HttpServletRequest, reason: String): SecurityContext? {
        request.getSession(false)?.invalidate()
        request.setAttribute(FAILURE_ATTRIBUTE, reason)
        return null
    }

    companion object {
        const val FAILURE_ATTRIBUTE = "paycore.admin.sessionFailure"
        const val SESSION_REPLACED = "SESSION_REPLACED"
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
    }
}
