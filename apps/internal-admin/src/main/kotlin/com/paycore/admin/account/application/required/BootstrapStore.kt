package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AdminActorId
import java.time.Instant

/**
 * 부트스트랩 1회성 고정 행(§4.9.1 `admin_bootstrap(id = 1)` — append-only SELECT·INSERT). [markExecuted]는 INSERT를 바로 실행해 PK 1062를
 * `UniqueConflict(BOOTSTRAP)`로 드러낸다(동시 실행 패자 — 빈 표 `COUNT … FOR UPDATE`에 기대지 않는다).
 */
interface BootstrapStore {
    fun markExecuted(executedAt: Instant, securityAdmin: AdminActorId, approver: AdminActorId)
}
