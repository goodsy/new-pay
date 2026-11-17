package com.paycore.admin.merchantops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 사업자등록번호 정확 일치 조회(BUSINESS-PROFILE-001 §7.2 — 합친 MERCHANT-BIZ-LOOKUP-001 rev 0.3 §4.2 순서, 역할만 OPERATOR·APPROVER — D-BIZ-006 ①). oracle 취급(D-MOPS-003):
 * ① 앞단(ACTIVE + 업무 조회 역할 — 아니면 403, 감사 없음) ② 사업자 조회 권한(`BusinessPermissions.VIEW` — 아니면 403 + 감사 `FORBIDDEN`) ③ 형식/본문 해석 실패 → 400 + 감사
 * `INVALID_INPUT`(`brn=INVALID`) ④ 등록번호 버킷 공유(`RegistrationNumberBudget.admit` — 가맹점 BRN 검색·사업자 생성과 같은 버킷) ⑤ 사업자 조회 창(목록·상세·이력과 공유)
 * ⑥ MASTER(TX 밖) ⑦ 감사 `OK`(`brn=123*******;results=0|1`, 찾으면 targetRef = businessId)/`UNAVAILABLE` — 감사 실패면 결과를 돌려주지 않는다. **매 시도 감사**(action `BUSINESS_BRN_LOOKUP` — C-25).
 * 응답 = MASTER 문서(`items` 0/1 — `businessId`·상호·마스킹 번호·`registrationStatus`(D-BIZ-006 ③)), 찾음·못 찾음 같은 모양.
 */
interface BusinessLookupUseCase {
    /** [registrationNumber] null = 본문 해석 실패(단일 목적 endpoint라 413·415 뒤 모든 본문이 시도 — BIZ-LOOKUP C-5). 값은 감사·로그·응답에 원문으로 남지 않는다. */
    fun lookup(actorId: String, registrationNumber: String?, context: AdminRequestContext): BusinessQueryResult
}
