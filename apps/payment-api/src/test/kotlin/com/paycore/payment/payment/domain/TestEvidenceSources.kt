package com.paycore.payment.payment.domain

import java.time.Instant

/**
 * 시험 상태 준비용 종료 근거 출처(AUTH-001 rev 0.3 m-6 — Demo 값). domain은 새 근거마다 출처 기록을 요구한다. 상태 준비가 목적인 시험(근거 출처를 보지 않는 시험)은
 * 아래 확장 함수로 근거 종류에 맞는 Demo 출처를 붙인다. 출처 자체를 보는 시험(PaymentSpec·AuthorizationResolutionSpec·RequestedPaymentExpirySpec)은 domain API를 직접 쓴다.
 * 멤버 함수(인자 2개)가 우선하므로 이 확장은 근거 하나만 넘길 때만 선택된다.
 */
object TestEvidenceSources {
    val recordedAt: Instant = Instant.parse("2026-10-15T03:00:05Z")

    const val RECORDED_BY = "test:state-setup"

    fun of(evidence: TerminationEvidence): TerminationEvidenceSource {
        val kind = when (evidence) {
            TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED -> TerminationEvidenceSourceKind.ATTEMPT_ABANDON
            TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED -> TerminationEvidenceSourceKind.CHECKOUT_EXPIRY
            else -> TerminationEvidenceSourceKind.OBSERVATION
        }
        return TerminationEvidenceSource(kind, "demo-evidence-source", recordedAt, RECORDED_BY)
    }
}

fun Payment.fail(evidence: TerminationEvidence?) = fail(evidence, evidence?.let(TestEvidenceSources::of))

fun Payment.expire(evidence: TerminationEvidence?) = expire(evidence, evidence?.let(TestEvidenceSources::of))

fun Payment.addTerminationEvidence(evidence: TerminationEvidence) = addTerminationEvidence(evidence, TestEvidenceSources.of(evidence))

/** 시험 상태 준비용 hold 출처 관찰(AUTH-001 rev 0.3 H-1·검토 #1 H-1 키 + variant — Demo). 출처 자체를 보는 시험은 Resolution 경로나 domain API를 직접 쓴다. */
val DEMO_HOLD_SOURCE = ObservationRef(ObservationKey(AttemptId("demo-att-setup"), ObservationSource.NOTIFICATION, "demo-setup-result"), 0)

fun OrderPaymentControl.reportSuccessAfterTermination(payment: Payment) = reportSuccessAfterTermination(payment, DEMO_HOLD_SOURCE)

fun OrderPaymentControl.reportConflictingResult(payment: Payment) = reportConflictingResult(payment, DEMO_HOLD_SOURCE)

fun OrderPaymentControl.completeAuthorization(payment: Payment) = completeAuthorization(payment, DEMO_HOLD_SOURCE)
