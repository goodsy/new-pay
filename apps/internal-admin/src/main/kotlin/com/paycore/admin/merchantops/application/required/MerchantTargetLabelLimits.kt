package com.paycore.admin.merchantops.application.required

/**
 * 화면 가맹점 라벨 API 속도 제한 값 Port(MERCHANT-CODE-001 §5.6.3 C-35 — 구현 `adapter.integration.MasterOpsSettings` `paycore.admin.master-ops.label-reads-per-minute`,
 * **Demo 값 60, 운영값 아님** — LIVE Gate, 기본값 없음). actor별 분 단위 고정 창(인스턴스 메모리). 가맹점 검색 창([MerchantOpsLimits.lookupPerMinute])과 **별개** —
 * 라벨이 운영자의 실제 가맹점 검색 한도를 깎지 않게. [MerchantOpsLimits]와 나눈 이유: 조회·등록 서비스와 그 시험 대역이 라벨 창을 모르게(ISP).
 */
interface MerchantTargetLabelLimits {
    val labelReadsPerMinute: Int
}
