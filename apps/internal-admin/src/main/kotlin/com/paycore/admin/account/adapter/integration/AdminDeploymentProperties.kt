package com.paycore.admin.account.adapter.integration

import org.springframework.boot.context.properties.ConfigurationProperties

/** `paycore.admin.deployment-mode`(필수 — 값이 없으면 바인딩 실패로 기동하지 않는다). */
@ConfigurationProperties(prefix = "paycore.admin")
class AdminDeploymentProperties(deploymentMode: DeploymentMode?) {
    val deploymentMode: DeploymentMode = requireNotNull(deploymentMode) { "paycore.admin.deployment-mode is required (DEMO or LIVE)" }

    override fun toString(): String = "AdminDeploymentProperties(deploymentMode=$deploymentMode)"
}
