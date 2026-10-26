package com.paycore.admin.account.domain

/**
 * 관리자 역할(D-ADMIN-002, §4.5.1 — 겸직 허용). `UNMASK`는 역할과 독립인 마스킹 해제 권한이며 부여는 `ROLE_GRANT`와 같은 2인 승인이다.
 * DB `active_roles`·`suspended_roles`(SET)와 `admin_role_event.role`의 값과 같다.
 */
enum class AdminRole { VIEWER, OPERATOR, APPROVER, SECURITY_ADMIN, UNMASK }
