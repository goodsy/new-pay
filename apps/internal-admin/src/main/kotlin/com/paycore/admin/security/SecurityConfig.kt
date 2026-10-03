package com.paycore.admin.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * 기본 거부 보안 골격.
 * - 모든 요청은 인증 필요. 아직 인증 방식(로그인 제공자·사용자 저장소)을 정하지 않았으므로 현재는 어떤 요청도 인증될 수 없다.
 * - CSRF는 Spring Security 기본값(활성)을 그대로 둔다. 비활성화하지 않는다.
 * - 인증 방식은 정해지지 않았다. formLogin/httpBasic/OAuth2를 여기서 임의로 켜지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .build()
}
