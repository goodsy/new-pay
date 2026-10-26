package com.paycore.admin.approval.application.required

/**
 * 2인 승인 지표 Port(§6 — S8). label은 고정 분류값만(동작·종류·결과·검사 열) — 승인 ID·actorId는 넣지 않는다. 로컬 TX가 끝난 뒤 호출한다.
 */
interface ApprovalMetrics {
    /**
     * [operation] `REQUEST`·`APPROVE`·`REJECT`·`WITHDRAW`·`EXPIRE_JOB`, [kind] 승인 종류 이름(모르면 `UNKNOWN`), [result] 결과 분류
     * (`CREATED`·`EXECUTED`·`REJECTED`·…·`RETRY_LATER`·`COMMIT_UNKNOWN`·`ERROR`).
     */
    fun decision(operation: String, kind: String, result: String)

    /** 1213·1205·version 충돌이 남아 409 `RETRY_LATER`. */
    fun retryLater(operation: String)

    /** 0단계와 1단계 사이 불변 열 불일치(rev 0.5 n-1 — **경보 대상**, 1건이라도 조사). [check]는 열 이름(`kind`·`target_actor_id`·`granted_role`). */
    fun integrityViolation(check: String)
}
