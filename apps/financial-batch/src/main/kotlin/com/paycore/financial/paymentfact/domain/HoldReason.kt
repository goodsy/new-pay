package com.paycore.financial.paymentfact.domain

/** 보류 사유(DB CHECK `ck_payment_fact_hold_reason`과 같은 집합). */
enum class HoldReason {
    ORIGINAL_UNKNOWN,
    ORIGINAL_MISMATCH,
    RETURN_SEQUENCE_CONFLICT,
    RETURN_EXCEEDS_AUTHORIZED,
    AUTHORIZATION_CONFLICT,
    IDENTITY_MISMATCH,
}
