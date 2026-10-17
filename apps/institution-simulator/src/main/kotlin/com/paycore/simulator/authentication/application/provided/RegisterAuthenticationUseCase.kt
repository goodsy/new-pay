package com.paycore.simulator.authentication.application.provided

import com.paycore.simulator.authentication.domain.RegistrationRequest
import com.paycore.simulator.authentication.domain.TransactionId
import java.time.Instant

/** 등록 결과. [created] = 새 거래(201), 아니면 같은 (client, state)·같은 내용의 기존 거래(200). */
data class Registration(val transactionId: TransactionId, val customerUrl: String, val expiresAt: Instant, val created: Boolean)

/** PG 서버의 인증 등록(Bearer 인증된 client). 거절은 SimulatorRejection. */
interface RegisterAuthenticationUseCase {
    fun register(clientId: String, request: RegistrationRequest): Registration
}
