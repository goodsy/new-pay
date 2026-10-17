package com.paycore.simulator.authentication.application.provided

import com.paycore.simulator.authentication.domain.LookupResult
import java.time.Duration

/** 조회 응답 방식(Recoverability 시나리오 — 첫 조회만 지연·5xx). */
sealed interface LookupAnswer {
    /** [delay] = 응답 전 대기(첫 조회 지연 시나리오, 그 밖 0). */
    data class Result(val result: LookupResult, val delay: Duration) : LookupAnswer

    data object ServerError : LookupAnswer
}

/** PG 서버의 결과 조회(읽기 전용, 다른 client의 거래는 존재 비노출 NOT_FOUND). */
interface LookupAuthenticationResultUseCase {
    fun lookup(clientId: String, resultRef: String): LookupAnswer
}
