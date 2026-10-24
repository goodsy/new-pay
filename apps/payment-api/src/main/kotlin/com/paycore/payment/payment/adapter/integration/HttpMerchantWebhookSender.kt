package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.MerchantWebhookSendRequest
import com.paycore.payment.payment.application.required.MerchantWebhookSendResult
import com.paycore.payment.payment.application.required.MerchantWebhookSender
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.WebhookAddressClass
import com.paycore.payment.payment.domain.WebhookAddressPolicy
import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.util.Timeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/**
 * 가맹점 HTTP 전송(WEBHOOK-001 §7.2·§21.0b, D-WH-007, 검토 m-4·m-5·m-6). **DB TX 밖에서만** 불린다.
 *
 * - URL 문법(요청마다, 해석 전): `https` + 443 + 소문자 host 이름만. IP 리터럴·userinfo·fragment·다른 scheme/port는 `DESTINATION_REJECTED`. 예외: **전달 환경이 TEST**이고 `host:port`가
 *   정확히 allowlist에 있으면 http·그 port 허용(시험 수신기) — LIVE 전달에는 적용하지 않는다(m-6).
 * - 이름 해석(Apache HttpClient `DnsResolver`): 예산(`dns-timeout`) 안에 해석하고 **모든** 주소가 공인일 때만 그 주소들로 연결한다(검사한 주소에 고정 — 재해석 없음, DNS rebinding 차단).
 *   TLS SNI·hostname 검증은 원 host 이름으로 한다(`DefaultClientTlsStrategy` 기본 검증기). allowlist 목적지(TEST)만 주소 판정을 건너뛰고 시험 CA를 추가로 믿을 수 있다.
 * - redirect를 따라가지 않는다(3xx = `REDIRECT`). 자동 재시도·쿠키·시스템 속성(프록시·JVM trust) 없음(ArchUnit — `useSystemProperties()` 금지).
 * - 시간: connect timeout, 응답 idle timeout, **전체 요청 기한**(예약된 abort — 느린 상태 줄·header도 끊는다). 상태 코드로 판정하고(WH-09) 본문은 읽지 않은 채
 *   요청을 abort한 뒤 닫는다(검토 #2 m-1 — 끝없는 느린 본문을 비우느라 기한까지 붙잡히지 않는다. 연결은 재사용하지 않는다).
 * - 해석 스레드는 [DNS_POOL_SIZE]개로 제한한다(검토 #2 n-3). 끊기지 않는 해석으로 모두 찼으면 새 스레드를 만들지 않고 `DNS_TIMEOUT`.
 * - 로그·결과에 URL·본문·서명 값·주소를 넣지 않는다(분류 코드만).
 */
class HttpMerchantWebhookSender(
    private val settings: MerchantWebhookTransportSettings,
    private val resolver: WebhookHostResolver,
    testTlsContext: SSLContext?,
) : MerchantWebhookSender, AutoCloseable {

    private val log = LoggerFactory.getLogger(HttpMerchantWebhookSender::class.java)
    private val allowlist: Set<String> = settings.testAllowlist.toSet()
    private val allowHosts: Set<String> = allowlist.map { it.substringBeforeLast(':') }.toSet()
    private val dnsExecutor = ThreadPoolExecutor(0, DNS_POOL_SIZE, 60, TimeUnit.SECONDS, SynchronousQueue()) { r -> Thread(r, "webhook-dns").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "webhook-deadline").apply { isDaemon = true } }

    /** LIVE·allowlist 밖 목적지: 공인 주소 판정 + JDK 기본 trust. */
    private val strict: CloseableHttpClient = client(SSLContext.getDefault()) { host -> publicOnly(host) }

    /** TEST allowlist 목적지만: 주소 판정 생략(정확한 host:port만 여기로 온다) + 시험 CA(있으면). */
    private val testAllowlisted: CloseableHttpClient = client(testTlsContext ?: SSLContext.getDefault()) { host ->
        if (host in allowHosts) budgeted(host) else publicOnly(host)
    }

    override fun send(request: MerchantWebhookSendRequest): MerchantWebhookSendResult {
        val uri = try {
            URI(request.url)
        } catch (e: URISyntaxException) {
            return rejected()
        }
        val host = uri.host ?: return rejected()
        val scheme = uri.scheme ?: return rejected()
        if (uri.rawUserInfo != null || uri.rawFragment != null || host != host.lowercase()) return rejected()
        val port = if (uri.port == -1) (if (scheme == "https") HTTPS_PORT else if (scheme == "http") HTTP_PORT else -1) else uri.port
        val allowlisted = request.environment == Environment.TEST && "$host:$port" in allowlist
        if (!allowlisted) {
            if (scheme != "https" || port != HTTPS_PORT || isIpLiteral(host)) return rejected()
        } else if (scheme != "https" && scheme != "http") {
            return rejected()
        }
        return execute(if (allowlisted) testAllowlisted else strict, uri, request)
    }

    private fun execute(client: CloseableHttpClient, uri: URI, request: MerchantWebhookSendRequest): MerchantWebhookSendResult {
        val post = HttpPost(uri)
        request.headers.forEach { (k, v) -> post.addHeader(k, v) }
        post.entity = ByteArrayEntity(request.body, null)
        val deadlineFired = AtomicBoolean(false)
        val deadline = deadlines.schedule({ deadlineFired.set(true); post.cancel() }, settings.requestDeadline.toMillis(), TimeUnit.MILLISECONDS)
        return try {
            val response = client.executeOpen(null, post, null)
            val status = response.code
            // m-1: 상태가 정해졌으면 본문을 읽지도 비우지도 않고 abort한 뒤 닫는다(close가 남은 본문을 끝까지 소비하지 않게). abort 뒤 close의 IOException은 결과와 무관하다.
            post.cancel()
            try {
                response.close()
            } catch (e: IOException) {
                // 이미 abort한 연결 — 무시.
            }
            if (deadlineFired.get()) return MerchantWebhookSendResult.Failed("TIMEOUT", null)
            when (status) {
                in 200..299 -> MerchantWebhookSendResult.Succeeded(status)
                in 300..399 -> MerchantWebhookSendResult.Failed("REDIRECT", status)
                else -> MerchantWebhookSendResult.Failed("HTTP_$status", status)
            }
        } catch (e: IOException) {
            classify(e, deadlineFired.get())
        } finally {
            deadline.cancel(false)
        }
    }

    private fun classify(e: IOException, deadlineFired: Boolean): MerchantWebhookSendResult.Failed {
        val chain = generateSequence<Throwable>(e) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSES).toList()
        val code = when {
            chain.any { it is WebhookDestinationBlockedException } -> "DESTINATION_BLOCKED"
            chain.any { it is WebhookDnsTimeoutException } -> "DNS_TIMEOUT"
            deadlineFired -> "TIMEOUT"
            chain.any { it is UnknownHostException } -> "DNS_FAILURE"
            chain.any { it is SSLException } -> "TLS"
            chain.any { it is InterruptedIOException } -> "TIMEOUT"
            else -> "CONNECTION"
        }
        if (code == "DESTINATION_BLOCKED") {
            log.warn("operation=MerchantWebhookSend result=DESTINATION_BLOCKED reason={}", chain.filterIsInstance<WebhookDestinationBlockedException>().first().reason)
        }
        return MerchantWebhookSendResult.Failed(code, null)
    }

    /** 예산 안의 해석(m-4). */
    private fun budgeted(host: String): List<InetAddress> {
        val future = try {
            dnsExecutor.submit<List<InetAddress>> { resolver.resolve(host) }
        } catch (e: RejectedExecutionException) {
            throw WebhookDnsTimeoutException()
        }
        return try {
            future.get(settings.dnsTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw WebhookDnsTimeoutException()
        } catch (e: ExecutionException) {
            throw (e.cause as? UnknownHostException) ?: UnknownHostException("name resolution failed")
        }
    }

    /** 해석된 모든 주소가 공인이어야 한다(D-WH-007 기본 거부). */
    private fun publicOnly(host: String): List<InetAddress> {
        val addresses = budgeted(host)
        return when (val c = WebhookAddressPolicy.classifyAll(addresses.map { it.address })) {
            WebhookAddressClass.Public -> addresses
            is WebhookAddressClass.Blocked -> throw WebhookDestinationBlockedException(c.reason)
        }
    }

    private fun client(tls: SSLContext, resolve: (String) -> List<InetAddress>): CloseableHttpClient {
        val dns = object : DnsResolver {
            override fun resolve(host: String): Array<InetAddress> = resolve(host).toTypedArray()

            override fun resolveCanonicalHostname(host: String): String = host
        }
        val manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(dns)
            .setTlsSocketStrategy(DefaultClientTlsStrategy(tls))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(settings.connectTimeout.toMillis()))
                    .setSocketTimeout(Timeout.ofMilliseconds(settings.responseTimeout.toMillis()))
                    .build(),
            )
            .build()
        return HttpClients.custom()
            .setConnectionManager(manager)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .build()
    }

    private fun rejected() = MerchantWebhookSendResult.Failed("DESTINATION_REJECTED", null)

    private fun isIpLiteral(host: String): Boolean = host.startsWith("[") || host.contains(':') || IPV4_LITERAL.matches(host) || NUMERIC_LAST_LABEL.matches(host)

    override fun close() {
        strict.close()
        testAllowlisted.close()
        dnsExecutor.shutdownNow()
        deadlines.shutdownNow()
    }

    internal companion object {
        /** 해석 스레드 상한(검토 #2 n-3). */
        const val DNS_POOL_SIZE = 8
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
        const val MAX_CAUSES = 20
        val IPV4_LITERAL = Regex("^[0-9.]+$")

        /** 마지막 label이 10진 숫자거나 0x 16진수면 WHATWG URL이 IPv4로 해석한다(D-MD-014와 같은 판정). */
        val NUMERIC_LAST_LABEL = Regex("^(?:.*\\.)?(?:[0-9]+|0x[0-9a-f]*)\\.?$")
    }
}
