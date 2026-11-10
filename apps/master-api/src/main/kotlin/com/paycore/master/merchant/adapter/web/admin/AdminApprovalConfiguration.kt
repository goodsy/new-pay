package com.paycore.master.merchant.adapter.web.admin

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 2인 승인 근거 실행 창 설정 바인딩(MERCHANT-OPS-002 §9.3 — [AdminApprovalSettings], 기본값 없음 → 값이 없으면 서블릿 웹 컨텍스트가 기동하지 않는다).
 * [AdminChannelConfiguration]과 따로 둔다: 서명 채널 설정 검사와 독립적으로 이 설정만의 기동 거부를 시험한다. 비웹 1회성 명령(demo-seed)에는 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AdminApprovalSettings::class)
class AdminApprovalConfiguration
