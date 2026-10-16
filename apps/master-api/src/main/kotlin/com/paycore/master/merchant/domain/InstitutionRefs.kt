package com.paycore.master.merchant.domain

/** PAYMENT 로컬 기관 프로파일 ID(SIMULATOR/REAL의 권위는 PAYMENT 프로파일 — 명세 §3.4). 1~64자 `[A-Za-z0-9._:-]`. */
@JvmInline
value class RouteRef(val value: String) {
    init {
        require(value.length in 1..MAX && value.all(::isRefChar)) { "routeRef must be 1..$MAX characters of [A-Za-z0-9._:-]" }
    }

    private companion object {
        const val MAX = 64
    }
}

/**
 * 기관 계약계정(기관 가맹점번호 등, D-MS2-005): Secret은 아니지만 **내부 식별자 등급** — 로그·지표·응답·예외 메시지에 원문을 쓰지 않는다.
 * 그래서 [toString]은 가린 값이다. 1~64자 `[A-Za-z0-9._:-]`.
 */
@JvmInline
value class InstitutionMerchantRef(val value: String) {
    init {
        require(value.length in 1..MAX && value.all(::isRefChar)) { "institutionMerchantRef must be 1..$MAX characters of [A-Za-z0-9._:-]" }
    }

    override fun toString(): String = "InstitutionMerchantRef(****)"

    private companion object {
        const val MAX = 64
    }
}

private fun isRefChar(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == ':' || c == '-'
