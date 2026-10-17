package com.paycore.payment.payment.domain

/** 반환 URL 거절 사유(내부 분류 — 외부 응답은 고정 code `INVALID_RETURN_URL`, AUTHN-001 명세 §5.3). */
enum class ReturnUrlRejection {
    TOO_LONG,
    NOT_PRINTABLE_ASCII,
    SCHEME,
    BACKSLASH,
    FRAGMENT,
    USERINFO,
    PORT,
    HOST_SYNTAX,
    IP_LITERAL,
    PATH_SYNTAX,
    QUERY_SYNTAX,
    PERCENT_ENCODING,
}

/** [MerchantReturnUrl.parse] 결과. */
sealed interface ReturnUrlParse {
    data class Accepted(val url: MerchantReturnUrl) : ReturnUrlParse

    data class Rejected(val reason: ReturnUrlRejection) : ReturnUrlParse
}

/**
 * 가맹점 successUrl/failUrl(D-AUTHN-002, AUTHN-001 명세 §8). 엄격 부분집합 문법에 **정확히** 맞을 때만 만들어진다 — 정규화하지 않고 거절한다
 * (D-MD-009 정신, AN-10): `https://` + host(MASTER `SiteChannel` WEB 문법과 같다 — 소문자, label 둘 이상, IP 리터럴 아님) + 선택 path + 선택 query.
 * 포트·userinfo·fragment·역슬래시·비ASCII·공백/제어문자·잘못된 percent-encoding은 모두 거절한다. 허용 판정은 [hasHost](Site WEB host와 문자열
 * 완전 일치, §8.2)까지 통과해야 한다. 순수 Kotlin(java.net 미사용 — RFC 3986 파서와의 교차 확인은 adapter 시험).
 */
class MerchantReturnUrl private constructor(val value: String, val host: String) {

    /** Site WEB host와 문자열 완전 일치(서브도메인·접미 일치 없음). */
    fun hasHost(siteHost: String): Boolean = host == siteHost

    /**
     * redirect용으로 [parameters]를 덧붙인 URL(§8.3): query가 없으면 `?`, 있으면 `&`, 값은 RFC 3986 unreserved 밖을 UTF-8 percent-encoding.
     * 이름은 코드 상수(unreserved만). 결과를 같은 문법으로 다시 파싱해 host가 그대로인지 확인한다. 2048자 제한은 저장 컬럼의 제한이라 구성 결과에는
     * [REDIRECT_MAX_LENGTH]를 쓴다(AN-24).
     */
    fun withQueryParameters(parameters: List<Pair<String, String>>): MerchantReturnUrl {
        require(parameters.isNotEmpty()) { "at least one parameter" }
        parameters.forEach { (name, _) -> require(name.isNotEmpty() && name.all(::isUnreserved)) { "parameter names must be RFC 3986 unreserved" } }
        val query = parameters.joinToString("&") { (name, v) -> name + "=" + percentEncode(v) }
        val composed = value + (if ('?' in value) "&" else "?") + query
        val reparsed = parse(composed, REDIRECT_MAX_LENGTH)
        check(reparsed is ReturnUrlParse.Accepted && reparsed.url.host == host) { "composed redirect must keep the stored host" }
        return reparsed.url
    }

    override fun equals(other: Any?): Boolean = other is MerchantReturnUrl && other.value == value

    override fun hashCode(): Int = value.hashCode()

    /** 진단용 — host만(가맹점 URL의 path·query는 로그·오류 화면에 남기지 않는다, §12.5). */
    override fun toString(): String = "MerchantReturnUrl(host=$host)"

    companion object {
        /** 저장 컬럼 VARCHAR(2048). */
        const val MAX_LENGTH = 2048

        /** 구성된 redirect URL 상한(AN-24). */
        const val REDIRECT_MAX_LENGTH = 8192

        private const val SCHEME = "https://"
        private const val MAX_HOST_LENGTH = 253
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

        /** WHATWG "ends in a number": 마지막 label이 10진수거나 `0x` 16진(빈 값 포함) — D-MD-014, `SiteChannel`과 같은 판정. */
        private val NUMERIC_LAST_LABEL = Regex("^([0-9]+|0x[0-9a-f]*)$")
        private const val SUB_DELIMS = "!$&'()*+,;="
        private val HEX = "0123456789ABCDEF"

        fun parse(raw: String): ReturnUrlParse = parse(raw, MAX_LENGTH)

        private fun parse(raw: String, maxLength: Int): ReturnUrlParse {
            fun reject(r: ReturnUrlRejection) = ReturnUrlParse.Rejected(r)
            if (raw.length > maxLength) return reject(ReturnUrlRejection.TOO_LONG)
            if (raw.any { it.code !in 0x21..0x7E }) return reject(ReturnUrlRejection.NOT_PRINTABLE_ASCII)
            if (!raw.startsWith(SCHEME)) return reject(ReturnUrlRejection.SCHEME)
            if ('\\' in raw) return reject(ReturnUrlRejection.BACKSLASH)
            if ('#' in raw) return reject(ReturnUrlRejection.FRAGMENT)
            val rest = raw.substring(SCHEME.length)
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val host = rest.substring(0, authorityEnd)
            when {
                '@' in host -> return reject(ReturnUrlRejection.USERINFO)
                '[' in host || ']' in host -> return reject(ReturnUrlRejection.IP_LITERAL)
                ':' in host -> return reject(ReturnUrlRejection.PORT)
                NUMERIC_LAST_LABEL.matches(host.substringAfterLast('.')) -> return reject(ReturnUrlRejection.IP_LITERAL)
                host.length > MAX_HOST_LENGTH || !HOST.matches(host) -> return reject(ReturnUrlRejection.HOST_SYNTAX)
            }
            val afterHost = rest.substring(authorityEnd)
            val queryStart = afterHost.indexOf('?')
            val path = if (queryStart < 0) afterHost else afterHost.substring(0, queryStart)
            val query = if (queryStart < 0) null else afterHost.substring(queryStart + 1)
            checkComponent(path) { isPathChar(it) }?.let { return reject(if (it) ReturnUrlRejection.PERCENT_ENCODING else ReturnUrlRejection.PATH_SYNTAX) }
            if (query != null) {
                checkComponent(query) { isPathChar(it) || it == '/' || it == '?' }
                    ?.let { return reject(if (it) ReturnUrlRejection.PERCENT_ENCODING else ReturnUrlRejection.QUERY_SYNTAX) }
            }
            return ReturnUrlParse.Accepted(MerchantReturnUrl(raw, host))
        }

        /** 위반이 없으면 null, percent-encoding 위반이면 true, 그 밖 문자 위반이면 false. path는 비었거나 "/"로 시작한다(호출자가 보장). */
        private inline fun checkComponent(s: String, allowed: (Char) -> Boolean): Boolean? {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length || !isHex(s[i + 1]) || !isHex(s[i + 2])) return true
                    i += 3
                    continue
                }
                if (!allowed(c)) return false
                i++
            }
            return null
        }

        /** segment 문자 + 구분자 "/"(path 전체를 한 번에 검사한다). */
        private fun isPathChar(c: Char): Boolean = isUnreserved(c) || c in SUB_DELIMS || c == ':' || c == '@' || c == '/'

        private fun isUnreserved(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private fun percentEncode(v: String): String {
            val sb = StringBuilder()
            for (b in Utf8Text.encode(v)) {
                val c = (b.toInt() and 0xFF)
                if (c < 0x80 && isUnreserved(c.toChar())) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
                }
            }
            return sb.toString()
        }
    }
}
