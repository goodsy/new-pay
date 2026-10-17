package com.paycore.payment.payment.domain

/** 인증 세션 결정 로그의 종류(`authentication_session_event.kind`, AUTHN-001 §10.1). */
enum class AuthenticationEventKind {
    CREATED, STARTED, VERIFIED, FAILED, EXPIRED, DUPLICATE_RESULT, IGNORED_LATE_RESULT, REJECTED_RESULT, LOOKUP_UNVERIFIABLE, REGISTRATION_FAILED,
}
