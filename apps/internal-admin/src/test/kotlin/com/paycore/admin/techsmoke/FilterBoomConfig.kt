package com.paycore.admin.techsmoke

import jakarta.servlet.Filter
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

/**
 * 시험 전용: MVC 밖(servlet filter 안)에서 예외를 던지는 경로([PATH]). 내장 Tomcat의 오류 dispatch(`/error`)가 고정 JSON 500을 내는지 실제 HTTP로 확인한다
 * (S4 리뷰 m-8 — `SpaServingSpec`). 예외 메시지에 시험 비밀을 넣어 응답에 새지 않는지 본다.
 */
@TestConfiguration(proxyBeanMethods = false)
class FilterBoomConfig {
    @Bean
    fun filterBoom(): FilterRegistrationBean<Filter> = FilterRegistrationBean<Filter>(Filter { _, _, _ ->
        throw IllegalStateException("filter failure ${TechSmokeApiController.SECRET_DETAIL}")
    }).apply {
        order = Ordered.HIGHEST_PRECEDENCE + 1
        addUrlPatterns(PATH)
    }

    companion object {
        const val PATH = "/api/tech-smoke/filter-boom"
    }
}
