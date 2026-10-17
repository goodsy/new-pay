package com.paycore.simulator.authentication.application

import com.paycore.simulator.authentication.application.provided.SimulatorClientAuthenticator
import com.paycore.simulator.authentication.application.required.SimulatorClients
import org.springframework.stereotype.Service

/** Bearer 키 → client ID(설정의 digest와 상수 시간 비교 — [SimulatorClients]). */
@Service
class SimulatorClientAuthenticationService(private val clients: SimulatorClients) : SimulatorClientAuthenticator {
    override fun authenticate(bearerKey: String): String? = clients.authenticate(bearerKey)?.id
}
