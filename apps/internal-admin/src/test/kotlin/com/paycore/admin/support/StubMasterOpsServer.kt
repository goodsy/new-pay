package com.paycore.admin.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MASTER 내부 조회 API 시험 대역(MERCHANT-OPS-001 — 실제 HTTP). 시험 설정 `paycore.admin.master-ops.base-url`의 고정 포트에서 한 번 뜨고 시험마다 응답을 바꾼다. 받은 요청
 * (method·경로·query·서명 헤더·본문)을 기록하고 서명은 **앱 코드와 독립으로**(javax.crypto 직접) 시험 키 mops-test-k1로 검증한다(`signatureValid`). 응답 본문은 시험이 준
 * 문자열 그대로(MASTER 계약 모양 — [detailJson] 등).
 */
object StubMasterOpsServer {
    const val PORT = 18494
    const val KEY_ID = "mops-test-k1"
    const val KEY_BASE64 = "dGVzdC1vbmx5LW1hc3Rlci1hZG1pbi1jaGFubmVsLWtleS0wMDAx"

    class Received(val method: String, val path: String, val query: String?, val signature: String?, val body: ByteArray, val signatureValid: Boolean)

    /**
     * [delayMillis]: 헤더 전 지연. [bodyDripMillis]: 헤더와 본문 첫 바이트를 보낸 **뒤** 나머지 본문을 이만큼 늦게 보낸다(본문 읽기 시간 상한 시험 — MINOR-3).
     */
    class Reply(val status: Int, val body: String, val delayMillis: Long = 0, val bodyDripMillis: Long = 0)

    val received = CopyOnWriteArrayList<Received>()
    private val replies = ConcurrentHashMap<String, Reply>()

    @Volatile
    private var server: HttpServer? = null

    @Synchronized
    fun start() {
        if (server != null) return
        server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0).apply {
            executor = Executors.newFixedThreadPool(8)
            createContext("/") { handle(it) }
            start()
        }
    }

    /** 경로별 응답. 등록되지 않은 경로는 404 `MERCHANT_NOT_FOUND`. 검색은 [SEARCH] 경로. */
    fun reply(path: String, reply: Reply) {
        replies[path] = reply
    }

    /**
     * MERCHANT-OPS-002 B2-2: 본문에 따라 응답하는 경로(승인 실행·기한 확인·결정 조회는 경로 하나를 모든 승인이 공유 — 시험이 approvalId별로 정한다). [reply]보다 먼저 본다.
     */
    private val dynamic = ConcurrentHashMap<String, (ByteArray) -> Reply>()

    fun replyWith(path: String, handler: (ByteArray) -> Reply) {
        dynamic[path] = handler
    }

    fun clearDynamic(path: String) {
        dynamic.remove(path)
    }

    fun requestsForPath(path: String): List<Received> = received.filter { it.path == path }

    private fun handle(exchange: HttpExchange) {
        exchange.use { ex ->
            val body = ex.requestBody.readAllBytes()
            val path = ex.requestURI.rawPath
            val signature = ex.requestHeaders.getFirst("PayCore-Admin-Signature")
            received += Received(ex.requestMethod, path, ex.requestURI.rawQuery, signature, body, verify(signature, ex.requestMethod, path, body))
            val reply = dynamic[path]?.invoke(body) ?: replies[path] ?: Reply(404, "{\"error\":\"MERCHANT_NOT_FOUND\"}")
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty() && reply.bodyDripMillis > 0) {
                ex.responseBody.write(bytes, 0, 1)
                ex.responseBody.flush()
                runCatching {
                    Thread.sleep(reply.bodyDripMillis)
                    ex.responseBody.write(bytes, 1, bytes.size - 1)
                }
            } else if (bytes.isNotEmpty()) {
                ex.responseBody.write(bytes)
            }
        }
    }

    private fun verify(header: String?, method: String, path: String, body: ByteArray): Boolean {
        val m = Regex("^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$").matchEntire(header ?: return false) ?: return false
        if (m.groupValues[2] != KEY_ID) return false
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canonical = listOf("paycore.admin-call.v1", method, path, m.groupValues[1], sha).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getDecoder().decode(KEY_BASE64), "HmacSHA256")) }
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } == m.groupValues[3]
    }

    const val SEARCH = "/internal/v1/admin/merchant-searches"

    /** MERCHANT-OPS-002 B1-1 명령 경로(서명 본문에 ID — C-2). 옛 사업자 등록 `businesses`는 BUSINESS-PROFILE-001 C-27 전환에서 제거. */
    const val MERCHANTS = "/internal/v1/admin/merchant-operations/merchants"
    const val SITES = "/internal/v1/admin/merchant-operations/merchant-sites"

    /** B1-2 명령 경로. */
    const val CONTRACTS = "/internal/v1/admin/merchant-operations/contracts"
    const val AGREEMENTS = "/internal/v1/admin/merchant-operations/service-agreements"
    const val CONTRACT_REVISIONS = "/internal/v1/admin/merchant-operations/contract-revisions"

    fun succeeded(reference: String, replayed: Boolean = false) = """{"outcome":"SUCCEEDED","reference":"$reference","replayed":$replayed}"""

    fun rejected(rejection: String, replayed: Boolean = false) = """{"outcome":"REJECTED","rejection":"$rejection","replayed":$replayed}"""
    const val INSTITUTION_CANARY = "SIMMERCH0001"

    fun merchantPath(merchantId: String) = "/internal/v1/admin/merchants/$merchantId"

    fun historyPath(merchantId: String, after: Long) = "/internal/v1/admin/merchants/$merchantId/history/after/$after"

    fun directoryJson(merchantId: String, businessId: String, masked: String = "123*******"): String =
        """{"items":[{"merchantId":"$merchantId","merchantName":"Demo Merchant","businessId":"$businessId","businessName":"Demo Business",""" +
            """"registrationNumberMasked":"$masked","lifecycle":"ACTIVE","entitlementRevision":5,"midCount":1,"createdAt":"2026-10-09T02:00:00.000Z","merchantCode":"shopabcd12"}],"nextCursor":null}"""

    const val EMPTY_DIRECTORY = """{"items":[],"nextCursor":null}"""

    /** B2-2 승인 경로(§9.4 — 모두 POST, 서명 본문). */
    const val CONTRACT_REVISION_APPROVALS = "/internal/v1/admin/merchant-operations/contract-revision-approvals"
    const val APPROVAL_DECISIONS = "/internal/v1/admin/merchant-operations/approval-decisions"
    const val DEADLINE_CONFIRMATIONS = "/internal/v1/admin/merchant-operations/approval-deadline-confirmations"

    /** B2-3 첫 Default MID 발급 실행 경로. */
    const val FIRST_DEFAULT_MERCHANT_ACCOUNTS = "/internal/v1/admin/merchant-operations/first-default-merchant-accounts"

    /** B3-1 명령 경로(1인 2 · 2인 2 — §9.14). */
    const val PAYMENT_METHOD_ACTIVATIONS = "/internal/v1/admin/merchant-operations/payment-method-activations"
    const val SITE_REVIEW_REJECTIONS = "/internal/v1/admin/merchant-operations/site-review-rejections"
    const val PAYMENT_METHOD_ACTIVATION_APPROVALS = "/internal/v1/admin/merchant-operations/payment-method-activation-approvals"
    const val SITE_REVIEW_APPROVALS = "/internal/v1/admin/merchant-operations/site-review-approvals"

    /** B3-2 통화 활성화(2인 — §9.14·§9.26). */
    const val CURRENCY_ENABLEMENTS = "/internal/v1/admin/merchant-operations/currency-enablements"

    /** B3-2 상세 통화 한 줄(기본 열린 KRW — [effectiveTo] null). */
    fun currencyJson(enablementId: String, effectiveFrom: String = "2026-10-09T02:00:00.000Z", effectiveTo: String? = null, currencyCode: String = "KRW"): String =
        """{"enablementId":"$enablementId","currencyCode":"$currencyCode","effectiveFrom":"$effectiveFrom","effectiveTo":${effectiveTo?.let { "\"$it\"" } ?: "null"},"endedAt":null}"""

    /** B3-1 Site·개통 등록자(상세 `sites[].registeredBy`·`paymentMethods[].registeredBy` — Demo 값, Revision 등록자와 다른 값). */
    const val SITE_REGISTRANT = "admin:adm_01JCSR0000000000000000SR01"

    /** 시험 Revision 등록자(기존 상세 Fixture — Demo 값). */
    const val REGISTRANT = "admin:adm_01JCRG0000000000000000RG01"

    /** 상세 Revision 한 줄(MASTER 계약 모양 — B2-2부터 `registeredBy` 포함). */
    fun revisionJson(
        revisionNo: Int, approvedAt: String? = "2026-10-09T02:00:00.000Z", effectiveFrom: String = "2026-10-09T02:00:00.000Z", effectiveTo: String? = null,
        registeredBy: String = REGISTRANT, scopeKind: String = "MERCHANT",
    ): String =
        """{"revisionNo":$revisionNo,"scopeKind":"$scopeKind","effectiveFrom":"$effectiveFrom","effectiveTo":${effectiveTo?.let { "\"$it\"" } ?: "null"},""" +
            """"approvedAt":${approvedAt?.let { "\"$it\"" } ?: "null"},"scopeSiteIds":[],"registeredBy":"$registeredBy"}"""

    /** 결정 조회 DECIDED 본문(MASTER 필드 순서 그대로). */
    fun decidedJson(fingerprint: String, reference: String? = null, rejection: String? = null, decidedVia: String = "EXECUTE", operation: String = "ApproveContractRevision"): String =
        """{"decision":"DECIDED","operation":"$operation","outcome":"${if (reference != null) "SUCCEEDED" else "REJECTED"}",""" +
            (if (reference != null) """"reference":"$reference",""" else """"rejection":"$rejection",""") +
            """"approvalFingerprint":"$fingerprint","decidedVia":"$decidedVia"}"""

    /**
     * MASTER 상세 계약 모양(카드 Route는 인수사·Route 참조·상태만). [routeExtra]로 허용되지 않는 필드를 끼워 넣어 fail-closed를 시험한다. [revisions]는 계약 하나의 Revision 목록 JSON
     * (기본 = 승인된 Revision 1, B2-2 기대값 변경: `registeredBy` 포함).
     * B3-1 fixture 추가(MASTER 상세 모양 변경과 같은 키): `sites[].registeredBy`, `paymentMethods[].registeredBy`, `accounts[].siteConfirmations[]`. [sites]·[assignments]·[paymentMethods]·
     * [siteConfirmations]를 주면 그 목록 JSON으로 바꾼다(B3 Fixture — 기본은 기존 값 + 새 필드).
     */
    fun detailJson(
        merchantId: String, routeExtra: String = "", revisions: String = revisionJson(1), sites: String? = null, assignments: String? = null, paymentMethods: String? = null,
        siteConfirmations: String? = null, currencies: String? = null, merchantCode: String = "shopabcd12", codeChangeable: Boolean = false, codeHistory: String? = null,
    ): String {
        val ulid = merchantId.removePrefix("mer_")
        // B3-2: [currencies]를 주면 그 목록 JSON(빈 문자열 = 통화 기록 없음), 기본은 기존 열린 KRW 한 줄.
        val currencyList = currencies ?: currencyJson("cur_$ulid")
        val siteList = sites ?: siteJson("site_$ulid")
        val assignmentList = assignments ?: assignmentJson("asg_$ulid", "site_$ulid")
        val methodList = paymentMethods ?: paymentMethodJson("act_$ulid", "ACTIVE")
        val confirmationList = siteConfirmations ?: siteConfirmationJson("sac_$ulid", "site_$ulid", "act_$ulid")
        // MERCHANT-CODE-001 MC-1(§3.8): MASTER 상세 모양 변화(merchant.merchantCode + 최상위 merchantCodeHistory·merchantCodeChangeable)를 대역도 따른다.
        return """{"merchant":{"merchantId":"$merchantId","name":"Demo Merchant","lifecycle":"ACTIVE","entitlementRevision":5,"createdAt":"2026-10-09T02:00:00.000Z","merchantCode":"$merchantCode"},""" +
            """"business":{"businessId":"biz_$ulid","name":"Demo Business","registrationNumberMasked":"123*******","lifecycle":"ACTIVE"},""" +
            """"sites":[$siteList],""" +
            """"contracts":[{"contractId":"ctr_$ulid","createdAt":"2026-10-09T02:00:00.000Z","revisions":[$revisions],""" +
            """"agreements":[{"agreementId":"csa_$ulid","serviceKind":"CARD_PG_PAYMENT","addedAt":"2026-10-09T02:00:00.000Z"}]}],""" +
            """"accounts":[{"merchantAccountId":"mid_$ulid","lifecycle":"ACTIVE","isDefault":true,"issuingContractId":"ctr_$ulid","issuingAgreementId":"csa_$ulid","issuingRevisionNo":1,"issuedAt":"2026-10-09T02:00:00.000Z",""" +
            """"assignments":[$assignmentList],""" +
            """"paymentMethods":[$methodList],""" +
            """"currencies":[$currencyList],""" +
            """"cardRoutes":[{"activationId":"cra_$ulid","paymentMethod":"CARD","acquirerCode":"SIMCARD01","routeRef":"SIMVAN01","status":"ACTIVE","registeredAt":"2026-10-09T02:00:00.000Z"$routeExtra}],""" +
            """"siteConfirmations":[$confirmationList]}],""" +
            """"limits":[{"policyId":"tlp_$ulid","scopeKind":"CSA_DEFAULT","agreementId":"csa_$ulid","merchantAccountId":null,"currencyCode":"KRW","perTxKind":"LIMITED","perTxAmount":1000000,"dailyKind":"LIMITED","dailyAmount":5000000,"effectiveFrom":"2026-10-09T15:00:00.000Z","effectiveTo":null,"endedAt":null}],""" +
            """"propagation":{"latestRevision":5,"latestRevisionIssuedAt":"2026-10-09T02:00:00.000Z","latestAckRevision":null,"latestAckAt":null,"effectConfirmedAt":null,"effectBasis":null},""" +
            // MERCHANT-CODE-001 MC-1b: [merchantCode]·[codeChangeable]·[codeHistory](이력 JSON — 기본 seq 1 하나)로 변경 가능·변경 뒤 상태를 만든다.
            """"merchantCodeHistory":[${codeHistory ?: codeHistoryJson(merchantCode)}],""" +
            """"merchantCodeChangeable":$codeChangeable}"""
    }

    /** MC-1b 코드 이력 seq 1(등록). */
    fun codeHistoryJson(code: String): String =
        """{"code":"$code","seq":1,"source":"REGISTRATION","replacesCode":null,"assignedAt":"2026-10-09T02:00:00.000Z","assignedBy":"admin:adm_01JC00000000000000000000V1","reasonCode":null}"""

    /** MC-1b 코드 이력 seq 1 + seq 2(변경 — [old] → [new]). */
    fun changedHistoryJson(old: String, new: String): String = codeHistoryJson(old) + "," +
        """{"code":"$new","seq":2,"source":"CHANGE","replacesCode":"$old","assignedAt":"2026-10-10T02:00:00.000Z","assignedBy":"admin:adm_01JCAP0000000000000000APR1","reasonCode":"TYPO_CORRECTION"}"""

    /** MERCHANT-CODE-001 MC-1b 코드 변경 실행 경로(2인). */
    const val MERCHANT_CODE_CHANGES = "/internal/v1/admin/merchant-operations/merchant-code-changes"

    /** B3-1 상세 Site 한 줄(기본 심사 APPROVED — [review] null이면 심사 결정 없음 = PENDING). */
    fun siteJson(
        siteId: String, lifecycle: String = "ACTIVE", review: String? = "APPROVED", registeredBy: String = SITE_REGISTRANT, identifier: String = "shop.example.test",
    ): String =
        """{"merchantSiteId":"$siteId","channelType":"WEB","channelIdentifier":"$identifier","lifecycle":"$lifecycle",""" +
            """"review":${review?.let { """{"decision":"$it","decidedAt":"2026-10-09T02:00:00.000Z"}""" } ?: "null"},"registeredBy":"$registeredBy"}"""

    /** B3-1 상세 Assignment 한 줄(기본 열린 PRIMARY). */
    fun assignmentJson(
        assignmentId: String, siteId: String, role: String = "PRIMARY", status: String = "ACTIVE", effectiveFrom: String = "2026-10-09T02:00:00.000Z", effectiveTo: String? = null,
    ): String =
        """{"assignmentId":"$assignmentId","merchantSiteId":"$siteId","role":"$role","status":"$status","effectiveFrom":"$effectiveFrom",""" +
            """"effectiveTo":${effectiveTo?.let { "\"$it\"" } ?: "null"}}"""

    /** B3-1 상세 개통 한 줄(`registeredBy` 포함). */
    fun paymentMethodJson(activationId: String, status: String?, registeredBy: String = SITE_REGISTRANT): String =
        """{"activationId":"$activationId","paymentMethod":"CARD","status":${status?.let { "\"$it\"" } ?: "null"},"registeredAt":"2026-10-09T02:00:00.000Z",""" +
            """"registeredBy":"$registeredBy"}"""

    /** B3-1 상세 Site 적용 확인 한 줄(근거·사유 없음). */
    fun siteConfirmationJson(confirmationId: String, siteId: String, activationId: String, revoked: Boolean = false): String =
        """{"confirmationId":"$confirmationId","merchantSiteId":"$siteId","paymentMethod":"CARD","activationId":"$activationId",""" +
            """"confirmedAt":"2026-10-09T02:00:00.000Z","revoked":$revoked}"""

    /** TXN-LIST-ENRICH-001 E-2 라벨 묶음 조회 경로(§4.2 — 서명 POST). */
    const val ACCOUNT_LABELS = "/internal/v1/admin/merchant-account-labels"

    /** 라벨 응답(MASTER 계약 모양 — 항목 3키). [items]는 (MID, 상호, 코드). */
    fun labelsJson(vararg items: Triple<String, String, String>): String =
        """{"items":[""" + items.joinToString(",") { (mid, name, code) -> """{"merchantAccountId":"$mid","merchantName":"$name","merchantCode":"$code"}""" } + "]}"

    /** MERCHANT-CODE-001 MC-3b 가맹점 내부 키 라벨 묶음 조회 경로(§5.6.3 — 서명 POST). */
    const val MERCHANT_LABELS = "/internal/v1/admin/merchant-labels"

    /** 가맹점 라벨 응답(MASTER 계약 모양 — 항목 3키). [items]는 (merchantId, 상호, 코드). */
    fun merchantLabelsJson(vararg items: Triple<String, String, String>): String =
        """{"items":[""" + items.joinToString(",") { (mer, name, code) -> """{"merchantId":"$mer","merchantName":"$name","merchantCode":"$code"}""" } + "]}"

    fun historyJson(merchantId: String): String =
        """{"items":[{"seq":7,"occurredAt":"2026-10-09T02:00:00.000Z","operation":"RegisterMerchant","aggregateType":"Merchant","aggregateId":"$merchantId","changeType":"MerchantRegistered","actorRef":"operator-1"}],"nextCursor":null}"""
}
