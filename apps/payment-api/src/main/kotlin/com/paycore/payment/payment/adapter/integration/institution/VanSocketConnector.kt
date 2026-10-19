package com.paycore.payment.payment.adapter.integration.institution

import java.net.InetSocketAddress
import java.net.Socket

/** 가상 VAN TCP 연결(JDK Socket — 새 의존성 없음, AU-8: 요청당 연결 1개). connect 실패는 IOException(한 바이트도 보내지 않음). 시험은 연결 횟수를 세는 대역을 쓴다. */
fun interface VanSocketConnector {
    fun connect(address: InetSocketAddress, timeoutMillis: Int): Socket

    companion object {
        val PLAIN = VanSocketConnector { address, timeout ->
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(address, timeout)
                socket
            } catch (e: java.io.IOException) {
                socket.close()
                throw e
            }
        }
    }
}
