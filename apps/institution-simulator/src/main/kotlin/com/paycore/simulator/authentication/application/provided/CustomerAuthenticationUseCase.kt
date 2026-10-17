package com.paycore.simulator.authentication.application.provided

import com.paycore.simulator.authentication.domain.TransactionId

/** 고객 화면 표시 값(카드번호 없음 — 가상 카드 참조 목록). */
data class CustomerView(
    /** 화면 경로용 원문 ID(템플릿이 value class 속성 이름 변형에 닿지 않게 문자열로 둔다). */
    val transactionId: String,
    val amountMinor: Long,
    val currency: String,
    val cardCompany: String,
    val open: Boolean,
    val cards: List<String>,
)

/** 고객 화면(인증 없음 — 거래 ID가 128bit 난수). 완료·취소는 PG 반환 URL(결과 참조 + state)을 돌려준다. */
interface CustomerAuthenticationUseCase {
    fun view(id: TransactionId): CustomerView

    fun authenticate(id: TransactionId, cardRef: String): String

    fun cancel(id: TransactionId): String
}
