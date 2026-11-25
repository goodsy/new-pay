package com.paycore.master.merchant.adapter.integration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 관리 명령 요청 지문 HMAC 키 설정(SEC-BIZ-001 M-2 — MERCHANT-OPS-002 C-46·L-23): `paycore.master.admin-channel.request-fingerprint-key`(Base64, 해독 32바이트 이상).
 * **기본값 없음** — 운영 `application.yml`에 값이 없고, 없으면 바인딩 실패로 기동하지 않는다(웹·Demo 1회성 명령 모두 — 둘 다 관리 명령 실행기를 쓴다).
 * 형식·길이·서명 키 분리·LIVE Demo 키 거부는 [HmacRequestFingerprintDigester]가 bean 생성 시 검사한다(값을 메시지에 넣지 않으려고 바인딩 단계에서 검사하지 않는다).
 * Demo/시험 값은 시험 설정(`src/test/resources/config/application.yml`)과 Demo 실행 환경에만 둔다(운영값 아님).
 */
@ConfigurationProperties(prefix = "paycore.master.admin-channel")
class RequestFingerprintSettings(val requestFingerprintKey: String) {
    override fun toString(): String = "RequestFingerprintSettings(requestFingerprintKey=****)"
}
