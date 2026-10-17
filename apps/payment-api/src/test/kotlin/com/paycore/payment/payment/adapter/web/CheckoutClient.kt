package com.paycore.payment.payment.adapter.web

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 결제창 connector 시험 client(src/test 전용): redirect를 따르지 않고 쿠키를 저장하지 않는다(시험이 `Cookie` 헤더를 직접 정한다). [raw]는 JDK client가 막는
 * `Host` 헤더 조작용 원시 HTTP/1.1 요청이다(m-9 시험).
 */
class CheckoutClient(private val port: Int) {
    data class Response(val status: Int, val headers: Map<String, List<String>>, val body: String) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        fun headerValues(name: String): List<String> = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
    }

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun get(path: String, cookie: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60)).GET()
        cookie?.let { b.header("Cookie", it) }
        headers.forEach { (k, v) -> b.header(k, v) }
        return send(b.build())
    }

    fun post(path: String, form: Map<String, String>, cookie: String? = null): Response {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        cookie?.let { b.header("Cookie", it) }
        return send(b.build())
    }

    /** 원시 요청 — 상태 줄과 헤더(소문자 이름)만 돌려준다. */
    fun raw(requestLine: String, hostHeader: String): Pair<String, Map<String, String>> = Socket("localhost", port).use { s ->
        s.soTimeout = 30_000
        s.getOutputStream().write("$requestLine\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
        val status = reader.readLine()
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        status to headers
    }

    private fun send(request: HttpRequest): Response {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return Response(response.statusCode(), response.headers().map(), response.body())
    }

    private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8)

    companion object {
        /** `Set-Cookie: __Host-pc_checkout_<handle>=<secret>; …` → `이름=값`(Cookie 헤더용). */
        fun cookiePair(setCookie: String): String = setCookie.substringBefore(';')
    }
}
