package com.paycore.admin.account.adapter.integration

/** 가드 통과 표시(다른 보안 bean의 의존 대상 — [LiveModeStartupGuard]가 먼저 실행된다). */
class LiveModeGuardPassed(val mode: DeploymentMode)
