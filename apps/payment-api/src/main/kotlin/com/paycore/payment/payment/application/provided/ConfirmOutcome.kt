package com.paycore.payment.payment.application.provided

/** confirm 성공 저장 결과 code(AUTH-001 §4.8 — 영속 값, V11 CHECK). 새 시작이면 ACCEPTED, 다른 키·같은 내용이면 진행 중 승인 재사용. */
enum class ConfirmOutcome { ACCEPTED, REUSED_ACTIVE_AUTHORIZATION }
