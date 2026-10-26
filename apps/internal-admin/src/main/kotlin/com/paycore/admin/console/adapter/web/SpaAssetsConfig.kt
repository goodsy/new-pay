package com.paycore.admin.console.adapter.web

import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Duration

/**
 * Vite 산출물 `/assets/` 아래 파일은 내용 해시 파일명이라 바뀌면 이름이 바뀐다 → `private, max-age=1년, immutable`(n-3).
 * 이 경로의 Security cache 헤더는 SecurityConfig의 assets 체인에서 끈다. `index.html`·SPA fallback·API는 Security 기본 `no-store`.
 */
@Configuration(proxyBeanMethods = false)
class SpaAssetsConfig : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
    }
}
