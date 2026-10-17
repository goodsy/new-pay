package com.paycore.payment.payment.domain

/** 조회를 확인할 수 없는 분류(AC1 §9.1 Unverifiable). HTTP 404도 여기다(AUTHN-001 m-5). */
enum class LookupFailure { TIMEOUT, CONNECTION, SERVER_ERROR, NOT_FOUND, MALFORMED }
