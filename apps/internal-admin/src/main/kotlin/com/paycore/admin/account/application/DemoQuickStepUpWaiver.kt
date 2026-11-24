package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.required.DemoQuickLoginSettings

/**
 * 재인증 생략 조건 하나(ADMIN-DEMO-LOGIN-001 결정 ③): 요청 세션이 데모 간편 로그인 세션 **그리고** 서버 켜짐(DEMO + enabled, 요청 시점).
 * [AdminOwnedCommandService.verifyStepUp](TOTP 검증 생략)과 [UnmaskService](빈 코드 400 생략)가 함께 쓴다.
 */
internal fun DemoQuickLoginSettings.waivesStepUp(context: AdminRequestContext): Boolean = active && context.demoQuickSession
