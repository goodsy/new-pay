package com.paycore.admin.paymentops.application

import com.paycore.admin.merchantops.application.provided.MerchantLabel
import com.paycore.admin.merchantops.application.provided.MerchantAccountIdRules

/**
 * 거래 목록·상세 문서에 가맹점 라벨을 붙이는 순수 규칙(TXN-LIST-ENRICH-001 §3.2·§3.3·§4.3·§4.5 — N-5: domain이 아니라 application에 둔다. 화면 응답 문서 모양을 아는 조립 규칙이고
 * Spring·HTTP·JSON 라이브러리를 쓰지 않는다). PAYMENT 문서는 이미 허용 필드 모양을 통과한 값이다(gateway).
 * - 라벨 대상: **PAYMENT 응답 항목의** `merchantAccountId` 중 MID 형식([MerchantAccountIdRules.FORMAT])만, 고유·오름차순(사용자 입력 MID가 아니다 — T-E3-1). 형식 밖 값은 그 항목만 `merchant: null`.
 * - [labels]가 null이면 UNAVAILABLE: 모든 항목 `merchant: null`, `merchantLabels: "UNAVAILABLE"`(틀린 상호를 붙이지 않는다 — 전부 아니면 전무). 아니면 `"OK"`(찾지 못한 MID는 null).
 * - 키 순서: 항목 = PAYMENT 키 그대로 + `merchant`(`{name, code}`), 최상위 = PAYMENT 키 그대로 + `merchantLabels`. 상세는 `payment` 객체 끝에 `merchant`, 최상위 끝에 `merchantLabels`.
 */
object TransactionListEnricher {
    const val OK = "OK"
    const val UNAVAILABLE = "UNAVAILABLE"

    @Suppress("UNCHECKED_CAST")
    fun listTargets(document: Map<String, Any?>): List<String> =
        (document["items"] as List<Map<String, Any?>>).mapNotNull { midOf(it) }.distinct().sorted()

    @Suppress("UNCHECKED_CAST")
    fun detailTarget(document: Map<String, Any?>): String? = (document["payment"] as? Map<String, Any?>)?.let(::midOf)

    @Suppress("UNCHECKED_CAST")
    fun list(document: Map<String, Any?>, labels: Map<String, MerchantLabel>?): Map<String, Any?> {
        val items = (document["items"] as List<Map<String, Any?>>).map { item -> LinkedHashMap(item).apply { put(MERCHANT, label(item, labels)) } }
        return LinkedHashMap(document).apply {
            put("items", items)
            put(LABELS, if (labels == null) UNAVAILABLE else OK)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun detail(document: Map<String, Any?>, labels: Map<String, MerchantLabel>?): Map<String, Any?> {
        val payment = document["payment"] as Map<String, Any?>
        return LinkedHashMap(document).apply {
            put("payment", LinkedHashMap(payment).apply { put(MERCHANT, label(payment, labels)) })
            put(LABELS, if (labels == null) UNAVAILABLE else OK)
        }
    }

    private fun midOf(item: Map<String, Any?>): String? = (item["merchantAccountId"] as? String)?.takeIf { MerchantAccountIdRules.FORMAT.matches(it) }

    private fun label(item: Map<String, Any?>, labels: Map<String, MerchantLabel>?): Map<String, String>? =
        midOf(item)?.let { mid -> labels?.get(mid) }?.let { linkedMapOf("name" to it.name, "code" to it.code) }

    private const val MERCHANT = "merchant"
    private const val LABELS = "merchantLabels"
}
