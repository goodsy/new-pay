package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.MerchantAccountIdRules
import com.paycore.admin.merchantops.application.provided.MerchantIdRules
import com.paycore.admin.merchantops.application.provided.MerchantLabelClient
import com.paycore.admin.merchantops.application.provided.MerchantLabelResult
import com.paycore.admin.merchantops.application.required.MasterLabelGateway
import org.springframework.stereotype.Service

/**
 * [MerchantLabelClient](TXN-LIST-ENRICH-001 §5): 입력 불변식(형식·중복·정렬·1..50) 확인 뒤 [MasterLabelGateway]에 위임한다. 권한·속도 제한·지표는 호출자(paymentops — 검색 창 1회가 상한,
 * C-4; MERCHANT-CODE-001 MC-3b 화면 라벨 API — 라벨 전용 분당 창 C-35), 감사 없음(D-MOPS-002 — 상호·코드는 VIEW 4역할이 이미 보는 값). 캐시 없음(C-2).
 */
@Service
class MerchantLabelService(private val gateway: MasterLabelGateway) : MerchantLabelClient {

    override fun labels(merchantAccountIds: List<String>): MerchantLabelResult {
        require(merchantAccountIds.size in 1..MerchantAccountIdRules.MAX_PER_CALL) { "1..${MerchantAccountIdRules.MAX_PER_CALL} merchant account ids" }
        require(merchantAccountIds.all { MerchantAccountIdRules.FORMAT.matches(it) }) { "merchant account id format" }
        require(merchantAccountIds.zipWithNext().all { (a, b) -> a < b }) { "distinct and ascending" }
        return gateway.accountLabels(merchantAccountIds)
    }

    override fun merchantLabels(merchantIds: List<String>): MerchantLabelResult {
        require(merchantIds.size in 1..MerchantIdRules.MAX_PER_CALL) { "1..${MerchantIdRules.MAX_PER_CALL} merchant ids" }
        require(merchantIds.all { MerchantIdRules.FORMAT.matches(it) }) { "merchant id format" }
        require(merchantIds.zipWithNext().all { (a, b) -> a < b }) { "distinct and ascending" }
        return gateway.merchantLabels(merchantIds)
    }
}
