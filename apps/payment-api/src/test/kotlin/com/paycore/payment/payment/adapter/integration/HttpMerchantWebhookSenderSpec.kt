package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.adapter.integration.ProgrammableMerchantReceiver.Behaviour
import com.paycore.payment.payment.application.required.MerchantWebhookSendRequest
import com.paycore.payment.payment.application.required.MerchantWebhookSendResult
import com.paycore.payment.payment.domain.Environment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 전송 adapter 시험(WEBHOOK-001 §7.2·§21.0b, D-WH-007, 검토 m-4·m-5·m-6) — 실제 소켓(loopback 시험 수신기)과 시험 CA. 외부망에 나가지 않는다: 이름 해석은 시험 resolver가 정하고,
 * 공인 주소로 해석되는 경우는 연결 전에 차단되거나 시험 수신기 allowlist(정확한 host:port, TEST 전달에서만)로만 loopback에 닿는다.
 * Demo 시간 값(시험 전용): connect 1s, 응답 idle 1s, 전체 기한 1.5s, DNS 0.5s, 응답 상한 4 KiB.
 */
class HttpMerchantWebhookSenderSpec : BehaviorSpec({

    val tls = WebhookTestTls.create(caLeaves = listOf("merchant.example.test", "other.example.test"), selfSigned = listOf("merchant.example.test"))
    val httpReceiver = ProgrammableMerchantReceiver.http()
    val httpsOk = ProgrammableMerchantReceiver.https(tls.serverContext("ca-leaf-merchant.example.test"))
    val httpsWrongHost = ProgrammableMerchantReceiver.https(tls.serverContext("ca-leaf-other.example.test"))
    val httpsSelfSigned = ProgrammableMerchantReceiver.https(tls.serverContext("self-merchant.example.test"))
    afterSpec {
        listOf(httpReceiver, httpsOk, httpsWrongHost, httpsSelfSigned).forEach { it.stop() }
        tls.close()
    }
    afterEach { listOf(httpReceiver, httpsOk, httpsWrongHost, httpsSelfSigned).forEach { it.reset() } }

    val loopback = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    val dns = ConcurrentHashMap<String, List<InetAddress>>()
    val lookups = AtomicInteger()
    val resolver = WebhookHostResolver { host ->
        lookups.incrementAndGet()
        dns[host] ?: throw java.net.UnknownHostException("no test mapping")
    }

    fun settings(allow: List<String>) = MerchantWebhookTransportSettings(
        connectTimeout = Duration.ofSeconds(1), responseTimeout = Duration.ofSeconds(1), requestDeadline = Duration.ofMillis(1_500), dnsTimeout = Duration.ofMillis(500),
        maxResponseBytes = 4_096, testAllowlist = allow, testTrustPem = null,
    )

    val allow = listOf(
        "127.0.0.1:${httpReceiver.port}", "merchant.example.test:${httpsOk.port}", "merchant.example.test:${httpsWrongHost.port}", "merchant.example.test:${httpsSelfSigned.port}",
    )
    val sender = HttpMerchantWebhookSender(settings(allow), resolver, tls.clientContext())
    val body = """{"eventId":"whe_x"}""".toByteArray()
    fun request(url: String, env: Environment = Environment.TEST) =
        MerchantWebhookSendRequest(env, url, linkedMapOf("Content-Type" to "application/json", "PayCore-Signature" to "t=1,kid=k,v1=00"), body)

    dns["merchant.example.test"] = listOf(loopback)
    dns["127.0.0.1"] = listOf(loopback)

    Given("an allowlisted TEST receiver over http") {
        Then("2xx is success; the exact body bytes and headers arrive") {
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Succeeded(200)
            val r = httpReceiver.received.single()
            r.body.contentEquals(body) shouldBe true
            r.headers["paycore-signature"] shouldBe "t=1,kid=k,v1=00"
            r.headers["content-type"] shouldBe "application/json"
        }
        Then("non-2xx is a failure with its status; 3xx is REDIRECT and is never followed") {
            httpReceiver.respond { Behaviour.Status(500) }
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("HTTP_500", 500)
            httpReceiver.respond { Behaviour.Redirect(302, "http://127.0.0.1:${httpReceiver.port}/elsewhere") }
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("REDIRECT", 302)
            httpReceiver.received.map { it.path } shouldBe listOf("/hook", "/hook")
        }
        Then("a dropped connection after receiving is CONNECTION (the merchant may have stored it — T-D2)") {
            httpReceiver.respond { Behaviour.DropAfterReceive }
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("CONNECTION", null)
            httpReceiver.received.size shouldBe 1
        }
        Then("a large response body is capped (status decides — WH-09)") {
            httpReceiver.respond { Behaviour.Status(200, ByteArray(200_000) { 'x'.code.toByte() }) }
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Succeeded(200)
        }
    }

    Given("time budgets (m-4)") {
        Then("a response slower than the idle timeout is TIMEOUT") {
            httpReceiver.respond { Behaviour.Delay(2_500) }
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("TIMEOUT", null)
        }
        Then("review #2 m-1: a 2xx followed by an endless slow body is settled on the status line and aborted (no drain) well within the deadline") {
            httpReceiver.respond { Behaviour.SlowDrip(chunks = 10_000, intervalMillis = 300) }
            val started = System.nanoTime()
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Succeeded(200)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            (elapsedMs < 1_000) shouldBe true
        }
        Then("status-line bytes dripped slower than the deadline but faster than the idle timeout are cut at the whole-request deadline (m-4)") {
            val slow = SlowHeadersServer.start(intervalMillis = 300)
            try {
                val local = HttpMerchantWebhookSender(settings(listOf("127.0.0.1:${slow.port}")), resolver, tls.clientContext())
                val started = System.nanoTime()
                local.send(request("http://127.0.0.1:${slow.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("TIMEOUT", null)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                (elapsedMs in 1_400..3_000) shouldBe true
            } finally {
                slow.stop()
            }
        }
        Then("review #2 n-3: the DNS pool is bounded — once it is saturated by stuck lookups, the next send is DNS_TIMEOUT instead of a new thread") {
            val stuck = java.util.concurrent.CountDownLatch(1)
            val saturating = HttpMerchantWebhookSender(settings(allow + listOf("stuck.example.test:443")), { host ->
                if (host == "stuck.example.test") {
                    while (stuck.count > 0) {
                        try {
                            stuck.await()
                        } catch (e: InterruptedException) {
                            // 끊기지 않는 해석(블로킹 DNS 흉내) — 인터럽트를 무시한다.
                        }
                    }
                }
                dns[host] ?: throw java.net.UnknownHostException("no test mapping")
            }, tls.clientContext())
            try {
                repeat(HttpMerchantWebhookSender.DNS_POOL_SIZE) {
                    saturating.send(request("https://stuck.example.test/hook")) shouldBe MerchantWebhookSendResult.Failed("DNS_TIMEOUT", null)
                }
                saturating.send(request("http://127.0.0.1:${httpReceiver.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("DNS_TIMEOUT", null)
                httpReceiver.received.size shouldBe 0
            } finally {
                stuck.countDown()
                saturating.close()
            }
        }
        Then("a name resolution slower than the DNS budget is DNS_TIMEOUT") {
            val slow = HttpMerchantWebhookSender(settings(listOf("slow.example.test:443")), { Thread.sleep(2_000); listOf(loopback) }, tls.clientContext())
            slow.send(request("https://slow.example.test/hook")) shouldBe MerchantWebhookSendResult.Failed("DNS_TIMEOUT", null)
        }
    }

    Given("destination rules (D-WH-007, m-6)") {
        Then("URL grammar: only https on 443 with a host name — http, other ports, IP literals, userinfo and fragments are rejected before any lookup") {
            val before = lookups.get()
            listOf(
                "http://merchant.example.test/hook", "https://merchant.example.test:8443/hook", "https://93.184.216.34/hook", "https://[2606:4700::1111]/hook",
                "https://user@merchant.example.test/hook", "https://merchant.example.test/hook#frag", "ftp://merchant.example.test/hook", "not a url",
            ).forEach { url -> sender.send(request(url)) shouldBe MerchantWebhookSendResult.Failed("DESTINATION_REJECTED", null) }
            lookups.get() shouldBe before
        }
        Then("a name resolving to any special-purpose address is blocked before connecting (all addresses must be public)") {
            dns["intranet.example.test"] = listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5)))
            dns["metadata.example.test"] = listOf(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())))
            dns["mixed.example.test"] = listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), loopback)
            listOf("intranet", "metadata", "mixed").forEach { h ->
                sender.send(request("https://$h.example.test/hook")) shouldBe MerchantWebhookSendResult.Failed("DESTINATION_BLOCKED", null)
            }
        }
        Then("the allowlist applies only to TEST deliveries: the same allowlisted host is blocked for a LIVE delivery (T-E1)") {
            sender.send(request("http://127.0.0.1:${httpReceiver.port}/hook", Environment.LIVE)) shouldBe MerchantWebhookSendResult.Failed("DESTINATION_REJECTED", null)
            sender.send(request("https://merchant.example.test:${httpsOk.port}/hook", Environment.LIVE)) shouldBe MerchantWebhookSendResult.Failed("DESTINATION_REJECTED", null)
            httpReceiver.received.size shouldBe 0
            httpsOk.received.size shouldBe 0
        }
    }

    Given("TLS (m-5)") {
        Then("the connection goes to the resolved (pinned) address while SNI and hostname verification use the original host name (n-4: one lookup per connection)") {
            val before = lookups.get()
            sender.send(request("https://merchant.example.test:${httpsOk.port}/hook")) shouldBe MerchantWebhookSendResult.Succeeded(200)
            lookups.get() - before shouldBe 1
            httpsOk.received.single().headers["host"] shouldBe "merchant.example.test:${httpsOk.port}"
        }
        Then("a certificate for another host name is rejected (TLS)") {
            sender.send(request("https://merchant.example.test:${httpsWrongHost.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("TLS", null)
            httpsWrongHost.received.size shouldBe 0
        }
        Then("a self-signed certificate is rejected (TLS)") {
            sender.send(request("https://merchant.example.test:${httpsSelfSigned.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("TLS", null)
        }
        Then("without the test trust (JDK default trust) the test CA is not trusted") {
            val strict = HttpMerchantWebhookSender(settings(allow), resolver, null)
            strict.send(request("https://merchant.example.test:${httpsOk.port}/hook")) shouldBe MerchantWebhookSendResult.Failed("TLS", null)
        }
    }

    Given("transport settings") {
        Then("invalid allowlist entries and inconsistent budgets are rejected; a JVM-wide custom trust store is refused (m-5)") {
            shouldThrow<IllegalArgumentException> { settings(listOf("no-port.example.test")) }
            shouldThrow<IllegalArgumentException> { settings(listOf("Upper.Example.test:443")) }
            shouldThrow<IllegalArgumentException> { settings(emptyList()).copy(requestDeadline = Duration.ofMillis(500)) }
            val previous = System.getProperty("javax.net.ssl.trustStore")
            System.setProperty("javax.net.ssl.trustStore", "/tmp/custom.p12")
            try {
                shouldThrow<IllegalArgumentException> { settings(emptyList()) }.message!!.contains("trustStore") shouldBe true
            } finally {
                if (previous == null) System.clearProperty("javax.net.ssl.trustStore") else System.setProperty("javax.net.ssl.trustStore", previous)
            }
        }
        Then("toString does not reveal the allowlist or trust material") {
            settings(allow).toString().contains("127.0.0.1") shouldBe false
        }
    }
})

/** 상태 줄을 한 바이트씩 [intervalMillis]마다 흘리는 시험 서버(idle timeout은 넘지 않고 전체 기한은 넘긴다 — m-4). */
private class SlowHeadersServer private constructor(private val socket: java.net.ServerSocket) {
    val port: Int get() = socket.localPort

    fun stop() = socket.close()

    companion object {
        fun start(intervalMillis: Long): SlowHeadersServer {
            val server = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            Thread {
                try {
                    while (!server.isClosed) {
                        val c = server.accept()
                        Thread {
                            c.use { conn ->
                                try {
                                    conn.getInputStream().read(ByteArray(8192))
                                    val out = conn.getOutputStream()
                                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".forEach { ch ->
                                        out.write(ch.code)
                                        out.flush()
                                        Thread.sleep(intervalMillis)
                                    }
                                } catch (e: java.io.IOException) {
                                    // 클라이언트가 기한에 끊었다 — 기대한 동작.
                                }
                            }
                        }.apply { isDaemon = true }.start()
                    }
                } catch (e: java.io.IOException) {
                    // 서버 종료.
                }
            }.apply { isDaemon = true }.start()
            return SlowHeadersServer(server)
        }
    }
}
