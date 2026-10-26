package com.paycore.admin.console

import com.paycore.admin.support.AdminDbTest
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import com.paycore.admin.techsmoke.FilterBoomConfig
import com.paycore.admin.techsmoke.TechSmokeApiController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * React SPA 같은 출처 서빙(ADMIN-BOOT-001 §4.10, n-3). 실제 Vite 빌드 산출물(processResources → static/)을 실제 HTTP로 받는다.
 *
 * - `/api/` 아래: 응답마다 `no-store`(Security 기본 cache 헤더), `immutable` 없음.
 * - `/assets/` 아래: 해시 파일명 → `private, max-age=31536000, immutable`, `no-store`·Pragma 없음. 없는 asset은 404(index로 대체하지 않음).
 * - `index.html`·SPA 경로 fallback: `no-store`.
 * - 보안 헤더(CSP·nosniff·Referrer-Policy·Permissions-Policy·frame 거부), 정적 요청은 세션을 만들지 않는다(n-1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AdminDbTest
@Import(FilterBoomConfig::class)
class SpaServingSpec(
    @LocalServerPort private val port: Int,
    @LocalManagementPort private val managementPort: Int,
    private val environment: Environment,
) : FunSpec({

    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    fun request(path: String, method: String = "GET"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        val built = if (method == "GET") builder.GET().build() else builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        return client.send(built, HttpResponse.BodyHandlers.ofString())
    }

    fun HttpResponse<String>.header(name: String): String? = headers().firstValue(name).orElse(null)

    val builtIndex = SpaServingSpec::class.java.getResource("/static/index.html").shouldNotBeNull().readText()
    val scriptAsset = Regex("""<script[^>]*\bsrc="(/assets/[^"]+\.js)"""").find(builtIndex).shouldNotBeNull().groupValues[1]

    test("the built index.html has no inline script or style (CSP 'self' only)") {
        Regex("""<script\b[^>]*>""").findAll(builtIndex).toList().forEach { it.value shouldContain "src=\"/assets/" }
        builtIndex shouldNotContain "<style"
        builtIndex shouldNotContain " style="
    }

    test("an exception inside a servlet filter is answered by the container error path with the fixed JSON 500 (real Tomcat — S4 review m-8)") {
        val response = request(FilterBoomConfig.PATH)
        response.statusCode() shouldBe 500
        response.body() shouldBe """{"error":"INTERNAL_ERROR"}"""
        response.header("Content-Type").orEmpty() shouldContain "application/json"
        response.body() shouldNotContain TechSmokeApiController.SECRET_DETAIL
    }

    test("GET / serves index.html with no-store") {
        val response = request("/")
        response.statusCode() shouldBe 200
        response.header("Content-Type").shouldNotBeNull() shouldStartWith "text/html"
        response.header("Cache-Control").shouldNotBeNull() shouldContain "no-store"
        response.body() shouldContain """<div id="root"></div>"""
        response.body() shouldContain scriptAsset
    }

    test("an SPA route falls back to index.html with no-store") {
        val response = request("/approvals/inbox")
        response.statusCode() shouldBe 200
        response.header("Cache-Control").shouldNotBeNull() shouldContain "no-store"
        response.body() shouldBe request("/").body()
    }

    test("hashed assets are cacheable as private, max-age=31536000, immutable without no-store") {
        val response = request(scriptAsset)
        response.statusCode() shouldBe 200
        val cacheControl = response.header("Cache-Control").shouldNotBeNull()
        cacheControl shouldContain "max-age=31536000"
        cacheControl shouldContain "private"
        cacheControl shouldContain "immutable"
        cacheControl shouldNotContain "no-store"
        cacheControl shouldNotContain "no-cache"
        response.header("Pragma") shouldBe null
    }

    test("a missing asset is 404 and is not replaced by index.html") {
        val response = request("/assets/missing-0000.js")
        response.statusCode() shouldBe 404
        response.body() shouldNotContain """<div id="root">"""
        // 오류 본문에 요청 경로를 되돌려주지 않는다(server.error.include-path=never — Boot 4.1.1 기본값은 always).
        response.body() shouldNotContain "missing-0000"
        response.header("Cache-Control").orEmpty() shouldNotContain "immutable"
        // Whitelabel HTML 오류 페이지도 내지 않는다(spring.web.error.whitelabel.enabled=false — Boot 4의 server.error.*는 폐기 키).
        val html = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/assets/missing-0000.js")).header("Accept", "text/html").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        html.statusCode() shouldBe 404
        html.body() shouldNotContain "Whitelabel"
        html.body() shouldNotContain "missing-0000"
        // 확장자 없는 assets 경로도 SPA fallback 대상이 아니다.
        request("/assets/no-extension").statusCode() shouldBe 404
    }

    test("API responses are no-store and never immutable") {
        val response = request("/api/console-probe")
        response.statusCode() shouldBe 401
        response.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
        val cacheControl = response.header("Cache-Control").shouldNotBeNull()
        cacheControl shouldContain "no-store"
        cacheControl shouldNotContain "immutable"
    }

    test("security headers are set on the SPA document") {
        val response = request("/")
        response.header("Content-Security-Policy") shouldBe CSP
        response.header("X-Content-Type-Options") shouldBe "nosniff"
        response.header("Referrer-Policy") shouldBe "no-referrer"
        response.header("X-Frame-Options") shouldBe "DENY"
        response.header("Permissions-Policy").shouldNotBeNull() shouldContain "camera=()"
    }

    test("static, SPA and rejected anonymous API requests do not create a session (no Set-Cookie)") {
        // n-1: 익명 요청마다 세션 행이 생기지 않게(401에 저장 요청 캐시 없음).
        listOf("/", "/approvals/inbox", scriptAsset, "/assets/missing-0000.js", "/api/console-probe").forEach { path ->
            request(path).headers().allValues("Set-Cookie").shouldBeEmpty()
        }
    }

    test("anonymous state-changing requests are refused with one fixed status and never create a session") {
        // S1 리뷰 M1: 익명 POST/PUT/DELETE가 CSRF 토큰 저장 때문에 세션을 만들면 안 된다(n-1).
        // 앱 체인: CSRF 토큰 불일치 403(세션 없으면 토큰을 저장하지 않음). assets 체인: CSRF 끔, GET 외 denyAll → 익명은 401.
        val expected = mapOf("/" to 403, "/api/x" to 403, "/assets/x" to 401)
        for ((path, status) in expected) {
            for (method in listOf("POST", "PUT", "DELETE")) {
                val response = request(path, method = method)
                withClue("$method $path") {
                    response.statusCode() shouldBe status
                    response.headers().allValues("Set-Cookie").shouldBeEmpty()
                }
            }
        }
    }

    test("S8 D-15: actuator health is served only on the management port (anonymous GET health, no details, no session); everything else there is refused") {
        managementPort shouldNotBe port
        // 기본 바인딩은 loopback(application.yml `${PAYCORE_ADMIN_MANAGEMENT_ADDRESS:127.0.0.1}` — 시험 설정은 주소를 덮어쓰지 않는다).
        environment.getProperty("management.server.address") shouldBe "127.0.0.1"
        fun management(path: String, method: String = "GET"): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$managementPort$path")).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val health = management("/actuator/health")
        health.statusCode() shouldBe 200
        // 상세(components·details) 없음 — Boot 4.1.1은 probe 그룹 이름만 함께 낸다(실측).
        health.body() shouldBe """{"groups":["liveness","readiness"],"status":"UP"}"""
        health.headers().allValues("Set-Cookie").shouldBeEmpty()
        health.header("X-Content-Type-Options") shouldBe "nosniff"
        for (path in listOf("/actuator", "/actuator/env", "/actuator/info", "/actuator/health/db", "/", "/api/auth/csrf")) {
            withClue(path) { management(path).statusCode() shouldBe 401 }
        }
        withClue("POST health") { management("/actuator/health", "POST").statusCode() shouldBe 401 }
        // 웹 포트에는 actuator가 없다(그 경로는 허용 목록 밖 — 익명 401).
        request("/actuator/health").statusCode() shouldBe 401
    }

    test("S8: HSTS on every response of the web port (TLS ends at the proxy — forward headers are not trusted; plain-HTTP HSTS is ignored by browsers)") {
        listOf("/", "/api/console-probe", scriptAsset).forEach { path ->
            withClue(path) { request(path).header("Strict-Transport-Security") shouldBe "max-age=31536000 ; includeSubDomains" }
        }
    }

    test("actuator and other non-SPA GET paths are not served anonymously") {
        // S1 리뷰 m3: 익명 GET은 SPA 문서 경로 허용 목록만. actuator(S8부터 별도 management 포트 — D-15)·그 밖의 경로는 401 JSON.
        for (path in listOf("/actuator", "/actuator/health", "/actuator/env", "/favicon.ico", "/robots.txt")) {
            val response = request(path)
            withClue(path) {
                response.statusCode() shouldBe 401
                response.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
            }
        }
    }

    test("manipulated SPA paths never fall back to index.html") {
        // S1 리뷰 m6: 대문자·슬래시 없는 api·4단계·/error 직접 요청·끝 슬래시(정책: SPA 경로 아님 — 정규 경로만)·퍼센트 인코딩.
        val expected = mapOf(
            "/API/x" to 401,
            "/api" to 401,
            "/a/b/c/d" to 401,
            "/error" to 401,
            "/approvals/" to 401,
            "/%61pprovals" to 401,
            "/approvals%20inbox" to 401,
            "/approvals%2Finbox" to 400,
        )
        for ((path, status) in expected) {
            val response = request(path)
            withClue(path) {
                response.statusCode() shouldBe status
                response.body() shouldNotContain """<div id="root">"""
            }
        }
    }
}) {
    private companion object {
        const val CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
            "font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
    }
}
