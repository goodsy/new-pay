package com.paycore.payment.payment.application.provided

/** eligibility NotEligible 고정 분류(CP-CONTRACT-001 §9·§13). v1은 생성 시 한도를 검사하지 않으므로 한도 사유가 없다(§4). */
enum class NotEligibleReason { CURRENCY_NOT_ENABLED, METHOD_NOT_ENABLED, CONTRACT_NOT_EFFECTIVE, MERCHANT_SUSPENDED }
