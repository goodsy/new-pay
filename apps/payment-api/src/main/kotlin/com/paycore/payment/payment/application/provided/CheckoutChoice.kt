package com.paycore.payment.payment.application.provided

/** 결제창 선택지(제출 값·표시 문구). 카드사(발급사 코드)·Plan(`LUMP_SUM`·`INSTALLMENT_<개월>`). */
data class CheckoutChoice(val value: String, val label: String)
