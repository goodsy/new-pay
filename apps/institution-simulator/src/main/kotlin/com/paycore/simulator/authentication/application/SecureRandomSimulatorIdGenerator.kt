package com.paycore.simulator.authentication.application

import com.paycore.simulator.authentication.application.required.SimulatorIdGenerator
import com.paycore.simulator.authentication.domain.ResultRef
import com.paycore.simulator.authentication.domain.TransactionId
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/** 128bit 난수 식별자(JDK SecureRandom, base64url 22자). 거래 ID는 고객 화면 URL의 접근 통제를 겸하므로 추측할 수 없어야 한다. */
@Component
class SecureRandomSimulatorIdGenerator : SimulatorIdGenerator {
    private val random = SecureRandom()

    override fun transactionId(): TransactionId = TransactionId("sat_" + token())

    override fun resultRef(): ResultRef = ResultRef("srr_" + token())

    override fun evidenceRef(): String = "sim-ev-" + token()

    private fun token(): String = ByteArray(BYTES).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private companion object {
        const val BYTES = 16
    }
}
