package com.paycore.simulator.authentication.application.required

import com.paycore.simulator.authentication.domain.ResultRef
import com.paycore.simulator.authentication.domain.TransactionId

/** 128bit 난수 식별자 생성(운영: SecureRandom). 시험은 정해진 값을 쓴다(golden). */
interface SimulatorIdGenerator {
    fun transactionId(): TransactionId

    fun resultRef(): ResultRef

    fun evidenceRef(): String
}
