package com.paycore.archfixture.webhooktransport

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 시스템 속성(프록시·trustStore 등)을 읽는 HttpClient — 가맹점 Webhook 전송은 시스템 프록시·JVM 전역 trust를 쓰지 않는다(WEBHOOK-001 검토 m-5, D-WH-007).
 */
object SystemPropertiesHttpClient {
    fun build(): CloseableHttpClient = HttpClients.custom().useSystemProperties().build()
}
