package com.paycore.payment.payment.application.provided

/** 승인 적체 관찰(AUTH-001 §9 Observability — gauge 전용, 비권위). 지금 시각 기준 값이다. */
interface AuthorizationBacklogQuery {
    fun backlog(): AuthorizationBacklog
}
