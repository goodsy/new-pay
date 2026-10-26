package com.paycore.admin.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 비노출 묶음(S8 — §6 `AdminNonDisclosureSpec`, §4.7.7 `ResetCodeDisclosureSpec`의 로그 부분): 비밀번호·TOTP 코드·TOTP 시크릿(설정 키)·등록/재설정 코드·
 * 세션 ID·CSRF 토큰·암호화/HMAC 키·마스킹 해제 사유가 흐르는 모든 경로를 한 번 지나며 — 애플리케이션 로거(`com.paycore`)는 DEBUG로 —
 * ① 캡처 로그(메시지·인자·예외 사슬·MDC)에 0회, ② 감사·승인·등록 코드·세션 속성 행에 0회(마스킹 해제 사유는 그 감사 행의 `reason` 열에만 — 감사 의미),
 * ③ 1회 표시 값은 그 응답 하나에만 나온다. 틀린 JSON 본문(비밀번호를 따옴표 없는 토큰으로)도 지난다 — 파서 오류 문구가 토큰을 담는다.
 * 프레임워크 로거는 운영 설정 수준 그대로 본다(`org.springframework.web` DEBUG는 요청 단편을 남길 수 있어 운영 금지 — §25 기록).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AdminNonDisclosureSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun field(body: String, name: String): String = Regex("\"$name\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    fun AdminHttpClient.postJson(path: String, vararg pairs: Pair<String, String>): MockHttpServletResponse =
        perform(post(path).contentType(MediaType.APPLICATION_JSON).content(AdminHttpClient.json(*pairs)))
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun random() = UUID.randomUUID().toString().replace("-", "").take(10)
    // 6자리 숫자(TOTP 코드)는 다른 숫자열 안의 우연한 일치를 피하려고 앞뒤가 영숫자가 아닐 때만 일치로 본다.
    fun String.carries(value: String): Boolean =
        if (value.matches(Regex("[0-9]{6}"))) Regex("(?<![0-9A-Za-z])$value(?![0-9A-Za-z])").containsMatchIn(this) else contains(value)
    fun assertAbsent(where: String, text: String, values: Map<String, String>) =
        values.forEach { (name, value) -> withClue("$where contains $name") { text.carries(value) shouldBe false } }

    Given("one run through every secret-handling flow (application loggers at DEBUG)") {
        Then("no secret in logs or stored rows; one-time values only in their own response; the unmask reason only in its audit reason column") {
            val secrets = linkedMapOf<String, String>()
            val responses = mutableListOf<Pair<String, String>>()
            fun MockHttpServletResponse.recorded(label: String): MockHttpServletResponse = also { responses += label to it.contentAsString }
            fun AdminHttpClient.remember(prefix: String) {
                sessionCookie?.let { secrets["$prefix-session-cookie"] = it; secrets["$prefix-session-id"] = String(Base64.getDecoder().decode(it)) }
                xsrfToken?.let { secrets["$prefix-xsrf"] = it }
            }
            val firstEvent = AdminDbAccounts.root(container).use { (it.scalar("SELECT COALESCE(MAX(event_id), 0) FROM admin_audit_event") as Number).toLong() }
            val reason = "사유-비밀-ND-" + random()
            var approvalId = ""
            val capture = LogCapture.start("com.paycore")
            try {
                // ① 로그인(비밀번호·TOTP), 틀린 비밀번호, 틀린 JSON(비밀번호가 따옴표 없는 토큰)
                val saAccount = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
                secrets["sa-password"] = saAccount.password
                val sa = AdminHttpClient(mockMvc).also { it.csrf() }
                sa.password(saAccount.loginName, saAccount.password).recorded("sa-password")
                val saTotp = TestTotp.code(saAccount.secret).also { secrets["sa-totp"] = it }
                sa.totp(saTotp).recorded("sa-totp").status shouldBe 200
                sa.remember("sa")
                val wrong = ("nd-wrong-" + random()).also { secrets["wrong-password"] = it }
                AdminHttpClient(mockMvc).also { it.csrf() }.password(saAccount.loginName, wrong).recorded("wrong-password")
                val bare = ("ndBareToken" + random()).also { secrets["malformed-json-token"] = it }
                AdminHttpClient(mockMvc).also { it.csrf() }
                    .perform(post("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content("{\"loginName\":\"${saAccount.loginName}\",\"password\":$bare}"))
                    .recorded("malformed").status shouldBe 400

                // ② 계정 생성 → 등록 코드(생성 응답 1회) → 등록(비밀번호·TOTP 시크릿·확인 코드)
                val created = sa.postJson("/api/accounts", "loginName" to "nd-" + random(), "displayName" to "비노출 대상").recorded("create")
                val enrollmentCode = field(created.contentAsString, "enrollmentCode").also { secrets["enrollment-code"] = it }
                val targetId = field(created.contentAsString, "actorId")
                val browser = AdminHttpClient(mockMvc).also { it.csrf() }
                browser.postJson("/api/enroll/start", "code" to enrollmentCode).recorded("enroll-start")
                browser.remember("enroll")
                val newPassword = ("nd-enroll-password-" + random()).also { secrets["new-password"] = it }
                browser.postJson("/api/enroll/password", "password" to newPassword).recorded("enroll-password")
                val uri = field(browser.perform(post("/api/enroll/totp")).recorded("enroll-totp").contentAsString, "otpauthUri").replace("\\u0026", "&")
                secrets["totp-setup-key"] = Regex("secret=([A-Z2-7]+)").find(uri)!!.groupValues[1]
                val confirm = TestTotp.code(TestTotp.secretOf(uri)).also { secrets["enroll-totp"] = it }
                browser.postJson("/api/enroll/complete", "totpCode" to confirm).recorded("enroll-complete").status shouldBe 200

                // ③ 재설정 승인(HTTP) → 재설정 코드(승인 응답 1회), 재인증 TOTP
                approvalId = field(sa.postJson("/api/approvals", "kind" to "PASSWORD_RESET", "targetActorId" to targetId).recorded("reset-request").contentAsString, "approvalId")
                val approverAccount = ready.bootstrapped(AdminRole.APPROVER)
                val approver = loggedIn(approverAccount).also { it.remember("approver") }
                val stepUp = TestTotp.code(approverAccount.secret, Instant.now().plusSeconds(30)).also { secrets["approver-step-up"] = it }
                val approved = approver.postJson("/api/approvals/$approvalId/approve", "totpCode" to stepUp).recorded("approve")
                approved.status shouldBe 200
                secrets["reset-code"] = field(approved.contentAsString, "enrollmentCode")

                // ④ 마스킹 해제(사유 자유 문구 + 재인증) — 틀린 재인증 1회(계수 경로)와 성공 1회
                val unmaskerAccount = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.UNMASK) }
                val unmasker = loggedIn(unmaskerAccount).also { it.remember("unmasker") }
                unmasker.postJson("/api/unmask", "targetRef" to "payment:nd-1", "reason" to reason, "totpCode" to "000000").recorded("unmask-wrong")
                val unmaskCode = TestTotp.code(unmaskerAccount.secret, Instant.now().plusSeconds(30)).also { secrets["unmask-step-up"] = it }
                unmasker.postJson("/api/unmask", "targetRef" to "payment:nd-1", "reason" to reason, "totpCode" to unmaskCode).recorded("unmask")
            } finally {
                capture.stop()
            }
            secrets["totp-cipher-key"] = TEST_TOTP_CIPHER_KEY
            secrets["login-name-hmac-key"] = TEST_LOGIN_NAME_HMAC_KEY

            // ① 로그: 비밀값·사유 0회
            val logs = capture.text()
            assertAbsent("log", logs, secrets + ("unmask-reason" to reason))

            // ② 저장 행: 감사(사유 열 제외)·승인·등록 코드·세션 속성에 비밀값 0회, 사유는 UNMASK_REQUESTED 성공 행의 reason 열에만
            AdminDbAccounts.root(container).use { db ->
                val audit = db.rowsText("SELECT * FROM admin_audit_event WHERE event_id > ?", firstEvent, except = setOf("reason"))
                val approval = db.rowsText("SELECT * FROM admin_approval_request WHERE approval_id = ?", approvalId)
                val codes = db.rowsText("SELECT * FROM admin_enrollment_code")
                val sessions = db.rowsText("SELECT * FROM admin_session_attributes")
                mapOf("audit" to audit, "approval" to approval, "enrollment codes" to codes).forEach { (table, text) -> assertAbsent(table, text, secrets + ("unmask-reason" to reason)) }
                // 세션 속성은 CSRF 토큰·세션 상태를 설계상 담는다 — 그 밖의 비밀(비밀번호·코드·시크릿 원문)이 없어야 한다(등록 진행 값은 암호문 — D-74).
                assertAbsent("session attributes", sessions, secrets.filterKeys { "session" !in it && "xsrf" !in it } + ("unmask-reason" to reason))
                db.rowsText("SELECT action, result_code, reason FROM admin_audit_event WHERE event_id > ? AND reason IS NOT NULL", firstEvent) shouldBe "UNMASK_REQUESTED|SUCCESS|$reason"
            }

            // ③ 응답: 1회 표시 값은 자기 응답에만, 나머지 비밀값·사유는 어떤 응답에도 없다
            val oneTime = mapOf("enrollment-code" to "create", "totp-setup-key" to "enroll-totp", "reset-code" to "approve")
            (secrets.filterKeys { "session" !in it && "xsrf" !in it } + ("unmask-reason" to reason)).forEach { (name, value) ->
                withClue("responses carrying $name") { responses.filter { (_, body) -> body.carries(value) }.map { it.first } shouldBe listOfNotNull(oneTime[name]) }
            }
        }
    }
}) {
    /** 시험 동안 루트 logger에 붙는 메모리 appender(메시지·인자·예외 사슬·MDC를 모두 문자열로). [debugLoggers]는 그동안 DEBUG. */
    class LogCapture private constructor(private val appender: ListAppender<ILoggingEvent>, private val restore: Map<Logger, Level?>) {
        fun stop() {
            root().detachAppender(appender)
            appender.stop()
            restore.forEach { (logger, level) -> logger.level = level }
        }

        fun text(): String = appender.list.joinToString("\n") { event ->
            buildString {
                append(event.formattedMessage)
                event.argumentArray?.forEach { append(" | ").append(it.toString()) }
                event.mdcPropertyMap?.forEach { (k, v) -> append(" | ").append(k).append('=').append(v) }
                appendThrowable(event.throwableProxy)
            }
        }

        private fun StringBuilder.appendThrowable(proxy: IThrowableProxy?) {
            var current = proxy
            while (current != null) {
                append(" | ").append(current.className).append(": ").append(current.message)
                current.suppressed?.forEach { appendThrowable(it) }
                current = current.cause
            }
        }

        companion object {
            private fun root(): Logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

            fun start(vararg debugLoggers: String): LogCapture {
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                root().addAppender(appender)
                val restore = debugLoggers.associate { name -> (LoggerFactory.getLogger(name) as Logger).let { it to it.level } }
                restore.keys.forEach { it.level = Level.DEBUG }
                return LogCapture(appender, restore)
            }
        }
    }

    private companion object {
        /** 시험 설정(test classpath `config/application.yml`)의 시험 전용 키(운영 키 아님). */
        const val TEST_TOTP_CIPHER_KEY = "dGVzdC1vbmx5LXRvdHAtY2lwaGVyLWtleS0zMmJ5dGU="
        const val TEST_LOGIN_NAME_HMAC_KEY = "dGVzdC1vbmx5LWxvZ2luLW5hbWUtaG1hYy1rZXktMzI="
    }
}

/** 행들을 `|`로 이은 문자열(바이너리 열은 UTF-8로 — 원문 비밀이 들어 있으면 보이게). [except] 열은 뺀다. */
private fun Connection.rowsText(sql: String, vararg args: Any?, except: Set<String> = emptySet()): String =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs ->
            val meta = rs.metaData
            val lines = mutableListOf<String>()
            while (rs.next()) {
                lines += (1..meta.columnCount).filter { meta.getColumnLabel(it).lowercase() !in except }.joinToString("|") { col ->
                    when (val value = rs.getObject(col)) {
                        null -> ""
                        is ByteArray -> String(value, Charsets.UTF_8)
                        else -> value.toString()
                    }
                }
            }
            lines.joinToString("\n")
        }
    }
