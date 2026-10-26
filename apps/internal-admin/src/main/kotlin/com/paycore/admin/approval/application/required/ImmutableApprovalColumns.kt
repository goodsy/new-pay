package com.paycore.admin.approval.application.required

/** 승인 행의 생성 불변 열(승인 TX 0단계 비잠금 읽기 — 1단계 잠금 뒤 같은지 단언, rev 0.5 n-1). */
data class ImmutableApprovalColumns(val kind: String, val targetActorId: String, val grantedRole: String?)
