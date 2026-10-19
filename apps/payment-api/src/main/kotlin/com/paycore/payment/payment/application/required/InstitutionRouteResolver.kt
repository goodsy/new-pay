package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import java.time.Duration

/**
 * 기관 Route Capability(AUTH-001 §5.8 — PAYMENT 로컬 버전 설정, **Demo 값**). 통화 [KRW](D-AUTH1-002), cardType [CREDIT, CHECK]·할부는 신용만(D-AUTH1-003), 할부 개월은
 * 결제창 Plan Demo 정책과 같은 값이어야 한다(AU-31 — 일치 검사는 S5).
 */
data class RouteCapabilities(
    val confirmsNoEffect: Boolean,
    val closableAfter: Duration,
    val inquiryRetention: Duration,
    val sameIdResendSafe: Boolean,
    val authenticationEvidenceReusableForResend: Boolean,
    val supportedCurrencies: Set<CurrencyCode>,
    val supportedCardTypes: Set<CardType>,
    val installmentCardTypes: Set<CardType>,
    val installmentMonths: Set<Int>,
    /** 할부 최소금액(minor, Demo) — 결제창 Plan 정책과 같은 값(AU-31). */
    val installmentMinimumAmount: Long?,
)

/** 기관 Route 프로파일. Authorization `admission.protocolVersion`과 Attempt Route Snapshot(routeRef·profileVersion)에 고정한다. 연결 정보·키는 여기 없다(Adapter 내부). */
data class InstitutionRouteProfile(
    val routeRef: String,
    /** Route가 연결되는 환경(검토 #3 n-4 — Payment 환경과 다르면 TX1 503 fail-closed). */
    val environment: Environment,
    val institution: String,
    val protocolVersion: String,
    val profileVersion: String,
    val capabilities: RouteCapabilities,
)

/** routeRef → Route 프로파일. 모르는 routeRef는 null(fail-closed — confirm 503, 저장 없음, MS2 §13.2). */
fun interface InstitutionRouteResolver {
    fun resolve(routeRef: String): InstitutionRouteProfile?
}
