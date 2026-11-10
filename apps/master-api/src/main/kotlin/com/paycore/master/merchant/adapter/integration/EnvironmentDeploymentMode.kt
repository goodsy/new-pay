package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.adapter.batch.DemoCommandModeGate
import com.paycore.master.merchant.application.required.DeploymentModePort
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * [DeploymentModePort] 구현(MERCHANT-OPS-002 C-26, review n-d): 기존 DEMO 모드 판정 [DemoCommandModeGate.isDemo](환경 후처리기의 정적 함수 — 후처리기 자체는 bean이 아니라 Port 구현이 될 수 없다)에
 * 위임한다. 판정 = `paycore.master.deployment-mode`가 정확히 `DEMO`(대소문자 구분). 값이 없거나 다르면 false(LIVE 취급 — fail-closed).
 *
 * 위치(구현 결정, 명세 §9.3의 adapter/batch 대신 adapter/integration): ArchUnit `batchAdapterUsesOnlyApplication`이 batch adapter의 `application.required` 의존을 금지한다 —
 * driven Port 구현은 integration adapter(다른 Port 구현 [MicrometerMasterOperationalMetrics]와 같은 곳)에 둔다.
 */
@Component
class EnvironmentDeploymentMode(private val environment: Environment) : DeploymentModePort {
    override fun isDemo(): Boolean = DemoCommandModeGate.isDemo(environment)
}
