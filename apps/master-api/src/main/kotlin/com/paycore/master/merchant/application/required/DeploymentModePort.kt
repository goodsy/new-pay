package com.paycore.master.merchant.application.required

/**
 * 배포 모드(MERCHANT-OPS-002 C-26 — D-MOPS-008 ②, D-DEMO-001·005). 근거 없는 Demo seed 경로(근거 없는 계약 승인·첫 MID 발급)는 [isDemo]가 true일 때만 실행한다.
 * 구현은 별도 adapter bean(`EnvironmentDeploymentMode`)이 기존 `DemoCommandModeGate.isDemo(environment)`(설정 `paycore.master.deployment-mode` = 정확히 `DEMO`)에 위임한다.
 * required Port다 — 구현이 없으면 기동하지 않는다(기본 true로 열리는 경로 없음).
 */
interface DeploymentModePort {
    fun isDemo(): Boolean
}
