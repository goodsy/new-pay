package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.domain.LoginName

/**
 * 데모 간편 로그인 기동 검사(ADMIN-DEMO-LOGIN-001 §2 C-2 — D-ADMIN-AUTH-DEMO-001 ②). [LiveModeStartupGuard]와 같은 방식(보고에는 **설정 이름만** — 계정 이름 값 없음)이며
 * 위반 목록은 따로 둔다(LIVE 가드 목록 불변):
 * - enabled=true인데 배치 모드가 DEMO가 아님(LIVE) → 거부.
 * - DEMO에서 enabled=true인데 허용 계정이 없거나 로그인 이름 형식이 아닌 항목이 있음 → 거부(켜졌는데 쓸 수 없는 설정을 조용히 넘기지 않는다).
 * 꺼져 있으면(없음·false) 두 모드 모두 통과한다.
 */
object DemoQuickLoginStartupGuard {
    private const val P = "paycore.admin.demo-quick-login."

    fun violations(mode: DeploymentMode, properties: DemoQuickLoginProperties): List<String> {
        if (!properties.enabled) return emptyList()
        if (mode != DeploymentMode.DEMO) return listOf("${P}enabled is not allowed outside DEMO")
        if (properties.accounts.isEmpty()) return listOf("${P}accounts is required when enabled")
        if (properties.accounts.any { !LoginName.FORMAT.matches(it) }) return listOf("${P}accounts contains an invalid login name")
        return emptyList()
    }

    /** 위반이 있으면 기동 실패(설정 이름만 담는다). */
    fun check(mode: DeploymentMode, properties: DemoQuickLoginProperties) {
        val found = violations(mode, properties)
        check(found.isEmpty()) { "demo quick login refused: " + found.joinToString("; ") }
    }
}
