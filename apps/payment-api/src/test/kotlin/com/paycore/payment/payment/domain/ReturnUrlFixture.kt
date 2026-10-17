package com.paycore.payment.payment.domain

/**
 * successUrl/failUrl Named Test Fixture(AUTHN-001 명세 §8.1 표, Test Fixture Policy — 업무 조건이 드러나는 명시 값). host는 Demo 값
 * `shop.example.test`(운영 가맹점 도메인 아님). 거절 행은 표의 한 줄 = 한 입력이며 기대 사유를 함께 적는다(의도가 드러나는 Invalid Fixture).
 */
object ReturnUrlFixture {
    const val SITE_HOST = "shop.example.test"

    /** §8.1 허용 표(문법 통과 — host 일치는 별도 판정). */
    val accepted: List<String> = listOf(
        "https://$SITE_HOST/pay/ok?x=1",
        "https://$SITE_HOST",
        "https://$SITE_HOST/",
        "https://$SITE_HOST?x=1",
        "https://$SITE_HOST/a/b/c?x=1&y=2&z=%2F",
        "https://$SITE_HOST/p;q=1/~user/a-b_c.d!$&'()*+,=:@/",
        "https://$SITE_HOST/pay?next=/a/b?c=d:e@f",
        "https://$SITE_HOST/%E2%82%A9",
        "https://xn--9n2bp8q.example.test/ok",
        "https://a.b-c.example.test/ok",
        "https://$SITE_HOST//double/slash",
    )

    /** §8.1 거절 표: 입력 → 기대 사유. */
    val rejected: List<Pair<String, ReturnUrlRejection>> = listOf(
        // https 소문자 + "//" 필수
        "http://$SITE_HOST/ok" to ReturnUrlRejection.SCHEME,
        "HTTPS://$SITE_HOST/ok" to ReturnUrlRejection.SCHEME,
        "javascript:alert(1)" to ReturnUrlRejection.SCHEME,
        "//$SITE_HOST/ok" to ReturnUrlRejection.SCHEME,
        "https:/$SITE_HOST/ok" to ReturnUrlRejection.SCHEME,
        "https:$SITE_HOST/ok" to ReturnUrlRejection.SCHEME,
        "" to ReturnUrlRejection.SCHEME,
        // userinfo
        "https://user@$SITE_HOST/" to ReturnUrlRejection.USERINFO,
        "https://$SITE_HOST@evil.example/" to ReturnUrlRejection.USERINFO,
        "https://user:pw@$SITE_HOST/" to ReturnUrlRejection.USERINFO,
        // 포트(기본 포트 포함)
        "https://$SITE_HOST:443/" to ReturnUrlRejection.PORT,
        "https://$SITE_HOST:8443/" to ReturnUrlRejection.PORT,
        "https://$SITE_HOST:/" to ReturnUrlRejection.PORT,
        // 정규화 없음: 대문자·끝 점·비ASCII
        "https://SHOP.example.test/" to ReturnUrlRejection.HOST_SYNTAX,
        "https://$SITE_HOST./" to ReturnUrlRejection.HOST_SYNTAX,
        "https://샵.example.test/" to ReturnUrlRejection.NOT_PRINTABLE_ASCII,
        // IP 리터럴
        "https://127.0.0.1/" to ReturnUrlRejection.IP_LITERAL,
        "https://[::1]/" to ReturnUrlRejection.IP_LITERAL,
        "https://2130706433/" to ReturnUrlRejection.IP_LITERAL,
        "https://0x7f.0x1/" to ReturnUrlRejection.IP_LITERAL,
        "https://a.0x7f/" to ReturnUrlRejection.IP_LITERAL,
        "https://a.0x/" to ReturnUrlRejection.IP_LITERAL,
        // host의 percent-encoding
        "https://$SITE_HOST%2eevil.example/" to ReturnUrlRejection.HOST_SYNTAX,
        "https://shop%2Eexample.test/" to ReturnUrlRejection.HOST_SYNTAX,
        // 역슬래시(WHATWG가 "/"로 바꾸는 문자)
        "https://$SITE_HOST\\@evil.example/" to ReturnUrlRejection.BACKSLASH,
        "https://$SITE_HOST\\evil" to ReturnUrlRejection.BACKSLASH,
        "https://$SITE_HOST/a\\b" to ReturnUrlRejection.BACKSLASH,
        // 탭·CR·LF·공백·제어문자(브라우저가 지워 host가 바뀌는 우회)
        "https://$SITE_HOST\n.evil.example/" to ReturnUrlRejection.NOT_PRINTABLE_ASCII,
        "https://$SITE_HOST\t/" to ReturnUrlRejection.NOT_PRINTABLE_ASCII,
        "https://$SITE_HOST\r/" to ReturnUrlRejection.NOT_PRINTABLE_ASCII,
        "https://$SITE_HOST/a b" to ReturnUrlRejection.NOT_PRINTABLE_ASCII,
        "https://$SITE_HOST/\u0000" to ReturnUrlRejection.NOT_PRINTABLE_ASCII,
        "https://$SITE_HOST/\u007F" to ReturnUrlRejection.NOT_PRINTABLE_ASCII,
        // fragment
        "https://$SITE_HOST/#frag" to ReturnUrlRejection.FRAGMENT,
        "https://$SITE_HOST#frag" to ReturnUrlRejection.FRAGMENT,
        // 잘못된 percent-encoding
        "https://$SITE_HOST/a%zz" to ReturnUrlRejection.PERCENT_ENCODING,
        "https://$SITE_HOST/a%2" to ReturnUrlRejection.PERCENT_ENCODING,
        "https://$SITE_HOST/?q=%" to ReturnUrlRejection.PERCENT_ENCODING,
        // host 문법(빈 label·label 길이·하이픈 위치·빈 host)
        "https:///ok" to ReturnUrlRejection.HOST_SYNTAX,
        "https://a..example.test/" to ReturnUrlRejection.HOST_SYNTAX,
        "https://-a.example.test/" to ReturnUrlRejection.HOST_SYNTAX,
        "https://a-.example.test/" to ReturnUrlRejection.HOST_SYNTAX,
        // 한 label host(Site WEB host 문법은 label 둘 이상 — SiteChannel과 같은 판정)
        "https://localhost/" to ReturnUrlRejection.HOST_SYNTAX,
        "https://${"a".repeat(64)}.example.test/" to ReturnUrlRejection.HOST_SYNTAX,
        // path·query에 허용 밖 문자
        "https://$SITE_HOST/a\"b" to ReturnUrlRejection.PATH_SYNTAX,
        "https://$SITE_HOST/a<b>" to ReturnUrlRejection.PATH_SYNTAX,
        "https://$SITE_HOST/?q=a\"b" to ReturnUrlRejection.QUERY_SYNTAX,
        "https://$SITE_HOST/?q={x}" to ReturnUrlRejection.QUERY_SYNTAX,
    )

    /** label 63자·전체 253자 경계(허용). */
    val longestHost: String = (List(3) { "a".repeat(63) } + "b".repeat(61)).joinToString(".")

    /** 2048자 정확히(허용)와 2049자(거절). */
    fun urlOfLength(length: Int): String {
        val prefix = "https://$SITE_HOST/"
        return prefix + "a".repeat(length - prefix.length)
    }
}
