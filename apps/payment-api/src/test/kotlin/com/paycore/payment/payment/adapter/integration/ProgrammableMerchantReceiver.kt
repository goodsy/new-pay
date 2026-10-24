package com.paycore.payment.payment.adapter.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

/**
 * 시험 전용 가상 가맹점 수신기(WEBHOOK-001 §9 WH-15 — `ProgrammableVan` 선례, src/test). 실제 HTTP(S) 소켓으로 받은 요청(헤더·본문 바이트)을 기록하고, 시험이 정한 동작으로 답한다:
 * 상태 코드, 지연, 조금씩 흘리는 응답(slow-drip), redirect, 큰 본문, 응답 없이 연결 끊기(저장 후 응답 유실). loopback에만 바인딩한다.
 */
class ProgrammableMerchantReceiver private constructor(private val server: HttpServer) {

    data class Received(val path: String, val headers: Map<String, String>, val body: ByteArray)

    sealed interface Behaviour {
        data class Status(val code: Int, val body: ByteArray = ByteArray(0)) : Behaviour

        data class Delay(val millis: Long, val code: Int = 200) : Behaviour

        /** 200 헤더 뒤 [chunks]개 바이트를 [intervalMillis]마다 하나씩(socket idle timeout은 넘지 않지만 전체 기한은 넘긴다 — m-4). */
        data class SlowDrip(val chunks: Int, val intervalMillis: Long) : Behaviour

        data class Redirect(val code: Int, val location: String) : Behaviour

        /** 요청을 기록한 뒤 응답 없이 연결을 닫는다(가맹점은 받았지만 PG는 결과를 모른다 — T-D2). */
        data object DropAfterReceive : Behaviour
    }

    val received = ConcurrentLinkedQueue<Received>()
    private val behaviour = AtomicReference<(Received) -> Behaviour>({ Behaviour.Status(200) })

    val port: Int get() = server.address.port

    fun respond(next: (Received) -> Behaviour) = behaviour.set(next)

    fun reset() {
        received.clear()
        behaviour.set { Behaviour.Status(200) }
    }

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val r = Received(ex.requestURI.path, ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }, ex.requestBody.readAllBytes())
        received += r
        try {
            when (val b = behaviour.get()(r)) {
                is Behaviour.Status -> {
                    ex.sendResponseHeaders(b.code, if (b.body.isEmpty()) -1 else b.body.size.toLong())
                    if (b.body.isNotEmpty()) ex.responseBody.use { it.write(b.body) }
                }
                is Behaviour.Delay -> {
                    Thread.sleep(b.millis)
                    ex.sendResponseHeaders(b.code, -1)
                }
                is Behaviour.SlowDrip -> {
                    ex.sendResponseHeaders(200, b.chunks.toLong())
                    ex.responseBody.use { out ->
                        repeat(b.chunks) {
                            out.write('x'.code)
                            out.flush()
                            Thread.sleep(b.intervalMillis)
                        }
                    }
                }
                is Behaviour.Redirect -> {
                    ex.responseHeaders.add("Location", b.location)
                    ex.sendResponseHeaders(b.code, -1)
                }
                Behaviour.DropAfterReceive -> Unit
            }
        } catch (e: java.io.IOException) {
            // 클라이언트가 먼저 끊은 경우(기한·크기 상한) — 시험 대상의 정상 동작.
        } finally {
            ex.close()
        }
    }

    companion object {
        fun http(): ProgrammableMerchantReceiver = start(HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0))

        fun https(context: SSLContext): ProgrammableMerchantReceiver =
            start(HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { httpsConfigurator = HttpsConfigurator(context) })

        private fun start(server: HttpServer): ProgrammableMerchantReceiver {
            val receiver = ProgrammableMerchantReceiver(server)
            server.createContext("/") { receiver.handle(it) }
            server.executor = Executors.newCachedThreadPool()
            server.start()
            return receiver
        }
    }
}
