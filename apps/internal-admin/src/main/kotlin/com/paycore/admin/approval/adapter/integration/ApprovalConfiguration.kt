package com.paycore.admin.approval.adapter.integration

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** approval 설정 조립 + 만료 정리 job 스케줄링(job은 설정으로 켠다 — `ApprovalExpiryJob`). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalSettings::class)
@EnableScheduling
class ApprovalConfiguration
