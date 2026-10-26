package com.paycore.admin.account.adapter.integration

/** 배치 모드(§4.8.2 — `paycore.admin.deployment-mode`, 기본값 없음). LIVE는 [LiveModeStartupGuard]를 통과해야 기동한다. */
enum class DeploymentMode { DEMO, LIVE }
