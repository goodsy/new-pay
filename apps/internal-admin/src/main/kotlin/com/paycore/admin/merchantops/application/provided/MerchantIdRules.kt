package com.paycore.admin.merchantops.application.provided

/**
 * ADMIN 안 가맹점 내부 키 형식·라벨 묶음 상한 단일 상수(MERCHANT-CODE-001 §5.6.3 MC-3b — gateway·라벨 서비스·화면 라벨 API가 같은 값을 쓴다, MASTER `MerchantId`·`MERCHANT_LABELS_MAX`와
 * 같은 값). [MerchantAccountIdRules]와 같은 이유로 라벨 client와 분리한다(client 사용처 제한이 상수 참조까지 막지 않게).
 */
object MerchantIdRules {
    val FORMAT = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")

    /** 한 번에 묻는 가맹점 수 상한(화면이 50개씩 순차 호출 — §5.6.3). */
    const val MAX_PER_CALL = 50
}
