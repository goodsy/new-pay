package com.paycore.admin.account.application.provided

/**
 * 활성 역할 보유자 수 지표 갱신(D-ADMIN1-023 — ADMIN-BOOT-002). LIVE Gate 운영 하한(활성 SECURITY_ADMIN ≥ 2, 활성 APPROVER ≥ 2) 미만을 지표·경보로 드러낸다.
 * "활성" = 계정 `ACTIVE` + 현재 역할 보유(복구 조건 D-ADMIN1-010과 같은 정의). 읽기 전용 로컬 TX 하나, 호출자는 스케줄러(adapter.batch)와 시험뿐.
 * 결과는 역할 이름 → 수(SECURITY_ADMIN·APPROVER 두 항목).
 */
interface RefreshActiveRoleHoldersUseCase {
    fun refresh(): Map<String, Int>
}
