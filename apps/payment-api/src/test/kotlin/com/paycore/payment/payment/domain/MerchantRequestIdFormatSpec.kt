package com.paycore.payment.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

/**
 * 가맹점이 보내는 merchantOrderId·멱등키 형식(D-CPC-002, 사용자 확정 2026-10-06): 1~64자, 문자 집합 [A-Za-z0-9-_.:],
 * 공백·비ASCII·제어문자 거절, 대소문자 구분. 비교는 입력 그대로(D-CP-001: trim·대소문자 변환·정규화 없음).
 * 형식은 domain 값 객체 생성자에서 강제한다(관례, CP-CONTRACT-001 §3.3) — 어떤 입력 경로로도 형식 밖 ID로 OrderKey·멱등 키를 만들 수 없다.
 */
class MerchantRequestIdFormatSpec : BehaviorSpec({

    val builders: List<Pair<String, (String) -> Any>> = listOf(
        "merchantOrderId" to { v: String -> MerchantOrderId(v) },
        "idempotencyKey" to { v: String -> IdempotencyKey(v) },
    )

    builders.forEach { (name, build) ->
        Given("$name values inside D-CPC-002") {
            Then("1 character is accepted") { valueOf(build("a")) shouldBe "a" }
            Then("64 characters (upper bound) are accepted") {
                valueOf(build("x".repeat(64))) shouldBe "x".repeat(64)
            }
            Then("every allowed character class is accepted and kept as given") {
                valueOf(build("AZaz09-_.:")) shouldBe "AZaz09-_.:"
                valueOf(build("ORD-2026.10.06_001:a")) shouldBe "ORD-2026.10.06_001:a"
            }
        }

        Given("$name values outside D-CPC-002") {
            Then("empty and 65 characters are rejected") {
                shouldThrow<IllegalArgumentException> { build("") }
                shouldThrow<IllegalArgumentException> { build("x".repeat(65)) }
            }
            Then("spaces anywhere are rejected (not trimmed)") {
                listOf(" ", " a", "a ", "a b", " a", "a　").forEach { bad ->
                    shouldThrow<IllegalArgumentException> { build(bad) }
                }
            }
            Then("control characters are rejected") {
                listOf("a\tb", "a\nb", "a\rb", "a\u0000b", "a\u007Fb", "a\u0085b").forEach { bad ->
                    shouldThrow<IllegalArgumentException> { build(bad) }
                }
            }
            Then("non-ASCII characters are rejected, including look-alikes and surrogate pairs") {
                listOf("주문1", "é", "ｏrder", "Ａ", "order😀", "ı").forEach { bad ->
                    shouldThrow<IllegalArgumentException> { build(bad) }
                }
            }
            Then("ASCII symbols outside [-_.:] are rejected") {
                listOf("a/b", "a+b", "a@b", "a#b", "a%b", "a,b", "a;b", "a|b", "a\"b", "a'b", "a\\b", "a=b", "a*b", "a~b").forEach { bad ->
                    shouldThrow<IllegalArgumentException> { build(bad) }
                }
            }
            Then("the error message does not echo the rejected value") {
                val error = shouldThrow<IllegalArgumentException> { build("bad value<script>") }
                error.message!! shouldNotContain "bad value<script>"
                error.message!! shouldNotContain "<script>"
            }
        }

        Given("$name values that differ only by case (D-CP-001: compared as given)") {
            Then("they are different") {
                build("ORDER-1") shouldNotBe build("order-1")
                build("Ab") shouldNotBe build("aB")
            }
            Then("identical strings are equal") {
                build("ORDER-1") shouldBe build("ORDER-1")
            }
        }
    }
}) {
    private companion object {
        fun valueOf(id: Any): String =
            when (id) {
                is MerchantOrderId -> id.value
                is IdempotencyKey -> id.value
                else -> error("unexpected id type ${id::class}")
            }
    }
}
