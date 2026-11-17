package com.paycore.master.merchant.domain

/** 등록 담당자(사업자 측 담당자 — 내부 PayCore 담당자 아님, POL-003). [primary] = 기본 등록 담당자(현재 최대 1, REGISTERED는 정확히 1). */
data class BusinessContact(val name: PersonName, val email: EmailAddress, val phoneNumber: PhoneNumber, val primary: Boolean)