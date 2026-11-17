package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.MerchantId

/**
 * 승인·감사 화면 표시용 가맹점 내부 키(`mer_`) → 가맹점 라벨 묶음 조회(MERCHANT-CODE-001 §5.6.3 MC-3b, C-29 — CQRS 조회 측, jOOQ `adapter.query`, 읽기 전용·REPEATABLE READ
 * 한 문장). 판정용 권위 조회가 아니다. 진입점은 ADMIN 채널 `MerchantLabelController`만(ArchUnit). 결과에는 내부 키(ADMIN 대응용 — 화면 미표시)·상호·**현재** 가맹점 코드만 —
 * 사업자 키·사업자 정보·lifecycle·등록번호·코드 이력은 어떤 타입에도 없다. 없는 키는 결과에서 빠지고 TERMINATED 가맹점도 포함한다. 결과는 merchantId 오름차순. 감사 없음(D-MOPS-002).
 */
interface MerchantLabelQuery {
    /** [merchantIds] 1..[MERCHANT_LABELS_MAX]개, 중복 없음(위반 = `IllegalArgumentException` — 진입 해석기가 먼저 400으로 거른다). */
    fun labels(merchantIds: List<MerchantId>): List<MerchantLabel>
}

/** 한 번에 조회할 수 있는 가맹점 수 상한(§5.6.3 — 화면이 50개씩 나눠 부른다, ADMIN도 같은 값). */
const val MERCHANT_LABELS_MAX = 50
