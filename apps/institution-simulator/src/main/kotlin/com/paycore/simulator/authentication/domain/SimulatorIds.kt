package com.paycore.simulator.authentication.domain

private val TRANSACTION_ID = Regex("^sat_[A-Za-z0-9_-]{22}$")
private val RESULT_REF = Regex("^srr_[A-Za-z0-9_-]{22}$")

/** 가상 인증기관 거래 식별자(`sat_` + 128bit base64url). 고객 화면 URL에 실리므로 추측할 수 없는 난수다(§13.4). */
@JvmInline
value class TransactionId(val value: String) {
    init {
        require(TRANSACTION_ID.matches(value)) { "transaction id must be sat_ followed by 22 base64url characters" }
    }
}

/** 1회용 결과 참조(`srr_` + 128bit base64url). 고객 브라우저가 PG로 가져가고 PG가 서버 간 조회에 쓴다. */
@JvmInline
value class ResultRef(val value: String) {
    init {
        require(RESULT_REF.matches(value)) { "result reference must be srr_ followed by 22 base64url characters" }
    }
}
