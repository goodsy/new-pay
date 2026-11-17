package com.paycore.admin.merchantops.application.required

import com.paycore.admin.merchantops.application.provided.MerchantLabelResult

/**
 * MASTER 라벨 묶음 조회 Port(TXN-LIST-ENRICH-001 §4.2·§4.4 — 구현 `adapter.integration.HttpMasterOpsGateway`: 같은 서명 키, **라벨 전용** permit·read timeout(§3.6), 응답 상한,
 * 허용 필드 모양 + 이 요청의 답(대응) 검사 — 위반은 전부 UNREADABLE). [MasterOpsGateway]와 나눈 이유: 조회·명령 Port를 쓰는 서비스·시험 대역이 라벨을 모르게(ISP).
 * MERCHANT-CODE-001 §5.6.3(MC-3b): 같은 Port의 두 번째 메서드 [merchantLabels](가맹점 내부 키 기준 — 같은 permit·client·상한을 나눠 쓴다, C-34).
 * **DB TX 안에서 부르면 `IllegalStateException`**. 실패는 예외가 아니라 값.
 */
interface MasterLabelGateway {
    fun accountLabels(merchantAccountIds: List<String>): MerchantLabelResult

    /** [merchantIds]: `mer_` 형식, 서로 다른 1..50개, 오름차순. [MerchantLabelResult.Found.labels]의 키 = merchantId. */
    fun merchantLabels(merchantIds: List<String>): MerchantLabelResult
}
