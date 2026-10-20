package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancelCapability
import com.paycore.payment.payment.domain.CancelCapabilityResolution
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.ProviderRequestRef
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
    /** 취소 Capability(CANCEL-001 §6.6 CN-28 — sim-van-2 Route에만). null = 이 Route로 취소할 수 없다(fail-closed, 운영 기본값이 아니다). */
    val cancel: RouteCancelCapabilities? = null,
)

/**
 * Route 취소 Capability(CANCEL-001 §6.6 CN-28 — **Demo 값**, 버전 있음). [version]은 취소 시작이 고정하는 Capability 버전이고(D-CANCEL1-004 — Route profile-version과 별개
 * 감사값, 바꾸면 profile-version도 바꾼다), [confirmsNoEffect]·[closableAfter]·[inquiryRetention]은 취소 요청 ID의 무효과 종결 규약이다(승인 Capability와 따로).
 */
data class RouteCancelCapabilities(
    val version: String,
    val partialLumpSumCardTypes: Set<CardType>,
    val partialInstallmentCardTypes: Set<CardType>,
    val confirmsNoEffect: Boolean,
    val closableAfter: Duration,
    val inquiryRetention: Duration,
) {
    fun capability(): CancelCapability = CancelCapability(version, partialLumpSumCardTypes, partialInstallmentCardTypes)
}

/** 기관 Route 프로파일. Authorization `admission.protocolVersion`과 Attempt Route Snapshot(routeRef·profileVersion)에 고정한다. 연결 정보·키는 여기 없다(Adapter 내부). */
data class InstitutionRouteProfile(
    val routeRef: String,
    /** Route가 연결되는 환경(검토 #3 n-4 — Payment 환경과 다르면 TX1 503 fail-closed). */
    val environment: Environment,
    val institution: String,
    val protocolVersion: String,
    val profileVersion: String,
    val capabilities: RouteCapabilities,
) {
    /** 취소 시작이 쓰는 Capability(CANCEL-001 §6.1): sim-van-2 Route의 취소 블록만 — 아니면 Unavailable(503, 저장 없음). */
    fun cancelCapability(): CancelCapabilityResolution {
        val cancel = capabilities.cancel
        return if (protocolVersion == CANCEL_PROTOCOL && cancel != null) CancelCapabilityResolution.Resolved(cancel.capability()) else CancelCapabilityResolution.Unavailable
    }

    /** rev 0.3 m-4: 이 Route가 원 승인과 같은 기관·환경인가(다르면 취소 시작 503·전송 게이트 거부 — 기관 계정 동일은 취소 요청 namespace가 보장한다). */
    fun servesOriginal(original: ProviderRequestRef): Boolean = institution == original.institution && environment == original.environment

    companion object {
        /** 취소 메시지를 가진 계약(CANCEL-001 CN-20). */
        const val CANCEL_PROTOCOL = "sim-van-2"
    }
}

/** routeRef → Route 프로파일. 모르는 routeRef는 null(fail-closed — confirm 503, 저장 없음, MS2 §13.2). */
fun interface InstitutionRouteResolver {
    fun resolve(routeRef: String): InstitutionRouteProfile?
}
