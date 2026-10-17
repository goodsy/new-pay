package com.paycore.simulator.authentication.application.provided

/** PG client Bearer 키 인증(AN-20). 맞는 client가 없으면 null(401). */
interface SimulatorClientAuthenticator {
    fun authenticate(bearerKey: String): String?
}
