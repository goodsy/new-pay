package com.paycore.payment.payment.application.provided

/** failUrl의 `code` query 값(AUTHN-001 §6.3, AN-08). 고정 4종 — 내부 사유를 더 싣지 않는다. */
enum class CheckoutFailCode { USER_CANCELLED, AUTHENTICATION_FAILED, CHECKOUT_EXPIRED, PAYMENT_NOT_AVAILABLE }
