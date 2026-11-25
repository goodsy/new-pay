package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.DeploymentModePort
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.util.Base64

/**
 * 요청 지문 HMAC 조립(SEC-BIZ-001 M-2). 웹·비웹(Demo 1회성 명령) 컨텍스트 모두 — 관리 명령 실행기를 쓰는 곳이면 키가 필요하다.
 * 서명 키(admin-channel `signing-keys[*].secret` — 웹에서만 있음, 권리 dispatch `signing-key`)는 web adapter·dispatch 설정 타입에 의존하지 않고 [Binder]로 속성만 읽는다
 * (AdminChannelKeySeparation과 같은 방식). 해독할 수 없는 서명 키는 여기서 비교하지 않는다(그 설정의 자기 검사가 기동을 막는다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RequestFingerprintSettings::class)
class RequestFingerprintConfiguration {

    @Bean
    fun requestFingerprintDigester(settings: RequestFingerprintSettings, environment: Environment, deploymentMode: DeploymentModePort): HmacRequestFingerprintDigester {
        val binder = Binder.get(environment)
        val adminKeys = binder.bind(ADMIN_SIGNING_KEYS, Bindable.listOf(SigningKeySecret::class.java)).orElse(emptyList()).orEmpty().mapNotNull { it.secret }
        val dispatchKey = binder.bind(DISPATCH_KEY, String::class.java).orElse(null)
        val others = (adminKeys + listOfNotNull(dispatchKey)).mapNotNull { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        return HmacRequestFingerprintDigester(settings.requestFingerprintKey, others, deploymentMode)
    }

    /** 서명 키 목록 항목에서 비밀만(kid는 이 비교에 쓰지 않는다). */
    class SigningKeySecret(val secret: String?) {
        override fun toString(): String = "SigningKeySecret(****)"
    }

    private companion object {
        const val ADMIN_SIGNING_KEYS = "paycore.master.admin-channel.signing-keys"
        const val DISPATCH_KEY = "paycore.master.entitlement.dispatch.signing-key"
    }
}
