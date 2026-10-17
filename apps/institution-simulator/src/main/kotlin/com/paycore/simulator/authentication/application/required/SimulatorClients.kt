package com.paycore.simulator.authentication.application.required

/** 등록된 PG client(Bearer 키는 SHA-256 digest만 보관 — AN-20). [returnOrigin]은 그 client의 결제창 origin(반환 URL 고정 문법의 앞부분). */
data class SimulatorClient(val id: String, val returnOrigin: String)

/** client 인증과 Simulator 설정(Demo — 운영값 아님). */
interface SimulatorClients {
    /** Bearer 키 원문으로 client를 찾는다(digest 비교, 없으면 null). */
    fun authenticate(bearerKey: String): SimulatorClient?

    fun find(clientId: String): SimulatorClient?
}
