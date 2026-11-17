package com.paycore.master.merchant.domain

/**
 * 사업자 개인정보·식별번호 마스킹(명세 §9 — 노출 범위는 D-BIZ-006 ②, 세부 형식은 C-2). 값 객체 `toString`과 변경 이력(저장 시점 마스킹 — 원문 이력 없음)이 이 함수를 쓴다.
 * 사업자등록번호는 기존 [BusinessRegistrationNumber.masked](앞 3자리).
 */
object BusinessPiiMask {
    /** 첫 code point + 나머지 `*`(1글자면 `김*`). */
    fun name(value: String): String {
        if (value.isEmpty()) return "*"
        val first = value.codePointAt(0)
        val count = value.codePointCount(0, value.length)
        return String(Character.toChars(first)) + "*".repeat(maxOf(1, count - 1))
    }

    /** `a***@e***.com` — local 첫 글자, domain 첫 라벨 첫 글자, 마지막 라벨 유지. */
    fun email(value: String): String {
        val local = value.substringBefore('@')
        val labels = value.substringAfter('@', "").split('.')
        return local.take(1) + "***@" + labels.first().take(1) + "***." + labels.last()
    }

    /** 끝 4자리만(`****5678`). */
    fun phone(value: String): String = "****" + value.takeLast(PHONE_VISIBLE)

    /** 법인등록번호 앞 6자리 + `*******`. */
    fun corporateNumber(value: String): String = value.take(CORPORATE_VISIBLE) + "*".repeat(maxOf(0, value.length - CORPORATE_VISIBLE))

    /** 기본주소 앞 두 어절 + ` ***`. */
    fun baseAddress(value: String): String = value.split(' ').filter { it.isNotEmpty() }.take(ADDRESS_WORDS).joinToString(" ") + " ***"

    /** 상세주소는 전부 가린다. */
    fun detailAddress(@Suppress("UNUSED_PARAMETER") value: String): String = "***"

    private const val PHONE_VISIBLE = 4
    private const val CORPORATE_VISIBLE = 6
    private const val ADDRESS_WORDS = 2
}