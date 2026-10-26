package com.paycore.admin.account.domain

/**
 * 계정 상태(§4.3.1). `PENDING_ENROLLMENT`: 생성·재설정·재등록·복구 대기, `ACTIVE`: 로그인 가능, `LOCKED`: 실패 한도(자동 해제 없음 — D-ADMIN1-005),
 * `SUSPENDED`: 정지(1인 즉시 — D-ADMIN1-003, 해제는 2인 승인).
 */
enum class AccountStatus { PENDING_ENROLLMENT, ACTIVE, LOCKED, SUSPENDED }
