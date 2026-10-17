package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.PaymentPlanPolicy

/** 결제창에 보이는 카드사(발급사 코드 — 검증 결과 issuerCode와 대조, D-MS2-004). */
data class CardCompanyOption(val code: String, val name: String)

/**
 * 인증 프로파일(AN-21 — PAYMENT 로컬 버전 설정, 세션에 버전 저장): 카드사 allowlist와 Plan 사전 판정 정책(Demo, 운영값 아님). 환경별 인증 backend가 이 프로파일을 쓴다.
 */
data class AuthenticationProfile(val id: String, val version: String, val cardCompanies: List<CardCompanyOption>, val planPolicy: PaymentPlanPolicy) {
    init {
        require(cardCompanies.isNotEmpty() && cardCompanies.map { it.code }.toSet().size == cardCompanies.size) { "card companies must be non-empty and unique" }
    }

    fun allows(cardCompany: String): Boolean = cardCompanies.any { it.code == cardCompany }
}

/**
 * Payment 환경 → 인증 backend 프로파일(§13.6). 설정에 backend가 없는 환경(현재 LIVE)은 null — 결제창 422 CHECKOUT_NOT_SUPPORTED(fail-closed). 기동 시 검증이
 * LIVE의 Simulator·REAL backend를 거부하므로 null이 아닌 환경은 TEST뿐이다.
 */
interface AuthenticationProfiles {
    fun profileFor(environment: Environment): AuthenticationProfile?
}
