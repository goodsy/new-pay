package com.paycore.admin.merchantops.application.required

/**
 * 사업자 조회 창 값 Port(BUSINESS-PROFILE-001 §7.1 m-2 — 설정 `paycore.admin.merchant-ops.business-reads-per-minute`, 구현 `adapter.integration.BusinessOpsSettings`, 필수·기본값 없음).
 * actor별 분 단위 고정 창(인스턴스 메모리 — L-9). **목록·상세·이력·번호 조회(④단계)가 한 창을 공유**하고 가맹점 조회 창(`lookupPerMinute`)·라벨 창과 별개다.
 * Demo 값 30/분 — 운영값 아님(LIVE Gate, L-14).
 */
interface BusinessReadLimits {
    val businessReadsPerMinute: Int
}
