package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * 결제 현재 상태 조회(CP-QUERY-001 §4 — GetPayment, D-CP-003: 생성 응답과 분리). 인증 [Environment]·MID 범위 밖·없음·형식 밖 paymentId는 모두 [PaymentStatusResult.NotFound]
 * 하나로 돌려준다(존재 비노출 — §4.3). 조회는 비권위 표시값이며 상태를 바꾸지 않는다(TX를 열지 않는다 — Reader가 읽기 전용 TX를 소유).
 */
interface PaymentStatusQuery {
    fun find(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: String): PaymentStatusResult
}

sealed interface PaymentStatusResult {
    data class Found(val view: PaymentStatusView) : PaymentStatusResult

    data object NotFound : PaymentStatusResult

    /** 조회 저장소 일시 실패(연결·TX 시작/종료·잠금 대기 등 — 분류만, 원인 예외 없음). web은 503(조회 문구·Retry-After)으로 응답한다(CQ-11). */
    data class Unavailable(val reason: FailureKind) : PaymentStatusResult
}

/**
 * 한 스냅샷의 행끼리 어긋남(CQ-07 — 일관된 읽기에서는 결함일 때만 생긴다). 존재할 수 없는 금융 상태를 가맹점에 보이지 않도록 view를 만들지 않는다. 메시지는 규칙 이름만(값 없음).
 */
class PaymentStatusInconsistent(val rule: String) : IllegalStateException("payment status snapshot is inconsistent: $rule")
