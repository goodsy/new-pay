package com.paycore.admin.account.application.required

/**
 * 데모 간편 로그인 설정 Port(ADMIN-DEMO-LOGIN-001 §2 C-4 — **Demo 전용 임시 기능**). [active] = 배치 모드 DEMO **그리고** 명시 설정 enabled(adapter가 계산).
 * 거짓이면 application은 계정을 내지 않고 모든 간편 로그인을 거부한다(엔드포인트 미등록과 별개의 방어 심층). [allowedLoginNames]는 표시 순서 그대로.
 */
interface DemoQuickLoginSettings {
    val active: Boolean
    val allowedLoginNames: List<String>
}
