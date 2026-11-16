package com.paycore.admin.merchantops.application.provided

/**
 * ADMIN 안 MID 형식·라벨 묶음 상한 단일 상수(TXN-LIST-ENRICH-001 N-4 — gateway·라벨 서비스·paymentops enricher가 같은 값을 쓴다). 라벨 client와 분리한 이유: client 사용처 제한
 * (ArchUnit — `TransactionLookupService`만)이 상수 참조까지 막지 않게.
 */
object MerchantAccountIdRules {
    val FORMAT = Regex("^mid_[0-9A-HJKMNP-TV-Z]{26}$")

    /** 한 번에 묻는 MID 수 상한(목록 한 쪽 상한과 같다 — MASTER도 같은 값). */
    const val MAX_PER_CALL = 50
}
