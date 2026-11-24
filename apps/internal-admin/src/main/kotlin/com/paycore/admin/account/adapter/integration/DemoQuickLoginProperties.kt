package com.paycore.admin.account.adapter.integration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 데모 간편 로그인 설정(ADMIN-DEMO-LOGIN-001 §2 — D-ADMIN-AUTH-DEMO-001, **Demo 전용 임시 기능 — 운영 준비 전 반드시 끔**).
 * [enabled] 기본 false(안전한 쪽), [accounts] 허용 로그인 이름 목록(화면 표시 순서). 켜짐 = 배치 모드 DEMO **그리고** enabled([DemoQuickLoginCondition]).
 */
@ConfigurationProperties(prefix = "paycore.admin.demo-quick-login")
class DemoQuickLoginProperties(enabled: Boolean? = null, accounts: List<String>? = null) {
    val enabled: Boolean = enabled ?: false
    val accounts: List<String> = accounts.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

    override fun toString(): String = "DemoQuickLoginProperties(enabled=$enabled, accounts=${accounts.size})"
}
