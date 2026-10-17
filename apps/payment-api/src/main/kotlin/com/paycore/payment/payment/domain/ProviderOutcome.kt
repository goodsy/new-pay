package com.paycore.payment.payment.domain

/** 기관 조회 결과 outcome(Simulator 계약 v1 §13.4). */
enum class ProviderOutcome { AUTHENTICATED, FAILED, CANCELLED, EXPIRED }
