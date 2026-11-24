package com.paycore.admin.account.adapter.web

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * 데모 간편 로그인 엔드포인트 등록 조건(ADMIN-DEMO-LOGIN-001 §2 C-3): `paycore.admin.deployment-mode=DEMO` **그리고** `paycore.admin.demo-quick-login.enabled=true`.
 * 하나라도 아니면 [DemoQuickLoginController] bean이 없어 경로가 404다. 설정 형식 검사·LIVE 거부는 `DemoQuickLoginStartupGuard`(기동 실패)가 한다.
 * 웹 adapter는 integration 타입을 쓰지 않는다(ArchitectureSpec) — 모드는 문자열로 읽어 설정 바인딩의 관대 enum 변환과 **같은 정규화**(글자·숫자만 남기고 대문자 —
 * `demo`·`de-mo`·`D_EMO` → DEMO)로 비교한다(리뷰 NIT-1: 켜짐 조건 = enum 바인딩 결과). security 패키지의 `SecurityConfig.normalizedDeploymentMode`와 같은 규칙이며,
 * 모듈 방향(security → account) 때문에 그 함수를 가져오지 않고 여기 둔다.
 */
class DemoQuickLoginCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean = active(context.environment)

    companion object {
        fun active(environment: Environment): Boolean {
            val binder = Binder.get(environment)
            val mode = runCatching { binder.bind("paycore.admin.deployment-mode", String::class.java).orElse(null) }.getOrNull()?.filter { it.isLetterOrDigit() }?.uppercase()
            val enabled = runCatching { binder.bind("paycore.admin.demo-quick-login.enabled", Boolean::class.javaObjectType).orElse(false) }.getOrNull() == true
            return mode == "DEMO" && enabled
        }
    }
}
