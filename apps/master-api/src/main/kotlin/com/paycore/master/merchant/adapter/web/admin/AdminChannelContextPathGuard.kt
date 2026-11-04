package com.paycore.master.merchant.adapter.web.admin

/**
 * context path 금지(MERCHANT-OPS-001 §3, ADMIN-PAYMENT-OPS-001 L-3와 같은 이유): ADMIN은 요청 URI 경로를 서명하고 MASTER는 `servletPath + pathInfo`로 검증하므로 둘이 같아야 한다.
 * `server.servlet.context-path`가 비어 있지 않으면(루트 `/` 포함) 기동을 거부한다.
 */
class AdminChannelContextPathGuard(contextPath: String) {
    init {
        check(contextPath.isEmpty()) { "server.servlet.context-path must be empty: the admin call signature covers the request path" }
    }
}
