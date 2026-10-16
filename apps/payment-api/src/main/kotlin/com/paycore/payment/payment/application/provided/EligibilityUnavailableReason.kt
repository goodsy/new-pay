package com.paycore.payment.payment.application.provided

/** eligibility Unavailable 분류(CP-CONTRACT-001 §13, fail-closed). */
enum class EligibilityUnavailableReason { SNAPSHOT_MISSING, SNAPSHOT_EXPIRED, VERSION_UNSUPPORTED }
