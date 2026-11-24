package com.paycore.admin.account.application.provided

/**
 * 요청 문맥(감사 상관 ID·원격 IP). 원격 IP는 `remoteAddr`만(프록시 헤더 불신 — §4.8.1).
 * [demoQuickSession]: 요청 세션이 데모 간편 로그인 세션인가(ADMIN-DEMO-LOGIN-001 결정 ③ — 웹 adapter가 세션 표시로 채운다, 기본 false). 재인증 생략은 이것 **그리고**
 * 서버 켜짐 조건(DEMO + enabled)일 때만 — 판정은 account application([AdminOwnedCommandExecutor.verifyStepUp]).
 */
data class AdminRequestContext(val correlationId: String, val remoteIp: String?, val demoQuickSession: Boolean = false) {
    init {
        require(correlationId.isNotBlank() && correlationId.length <= 64) { "correlation id must be 1-64 characters" }
        require(remoteIp == null || remoteIp.length <= 45) { "remote ip must be at most 45 characters" }
    }
}
