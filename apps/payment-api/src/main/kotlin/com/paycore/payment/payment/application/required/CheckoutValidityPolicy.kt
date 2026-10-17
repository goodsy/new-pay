package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CheckoutWindow
import java.time.Duration
import java.time.Instant

/**
 * 결제 유효기간 설정(D-AUTHN-005, AUTHN-001 §7.5.1 — 버전 있는 설정, 운영 기본값 없음, 시험·로컬은 Demo 값). 새 Payment의 결제창 절대 만료 시각 =
 * 생성 시각 + [duration], 그 설정 [version]을 함께 저장한다.
 */
interface CheckoutValidityPolicy {
    val version: String
    val duration: Duration

    fun windowFor(createdAt: Instant): CheckoutWindow = CheckoutWindow(createdAt.plus(duration), version)
}
