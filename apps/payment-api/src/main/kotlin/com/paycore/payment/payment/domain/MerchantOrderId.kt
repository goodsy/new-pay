package com.paycore.payment.payment.domain

/**
 * 가맹점 주문번호. 입력 그대로 비교하고 정규화하지 않는다(D-CP-001). 형식은 D-CPC-002([MerchantRequestIdFormat])이며 생성자에서
 * 강제한다(관례, CP-CONTRACT-001 §3.3: OrderKey는 영구 DB 키라 어떤 입력 경로로도 형식 밖 값이 생기지 않게 한다).
 */
@JvmInline
value class MerchantOrderId(val value: String) {
    init {
        MerchantRequestIdFormat.requireValid(value, "merchantOrderId")
    }
}
