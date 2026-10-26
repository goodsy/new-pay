package com.paycore.admin.account.application.provided

/** 요청 문맥(감사 상관 ID·원격 IP). 원격 IP는 `remoteAddr`만(프록시 헤더 불신 — §4.8.1). */
data class AdminRequestContext(val correlationId: String, val remoteIp: String?) {
    init {
        require(correlationId.isNotBlank() && correlationId.length <= 64) { "correlation id must be 1-64 characters" }
        require(remoteIp == null || remoteIp.length <= 45) { "remote ip must be at most 45 characters" }
    }
}
