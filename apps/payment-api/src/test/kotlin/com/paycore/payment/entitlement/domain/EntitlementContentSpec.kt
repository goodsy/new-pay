package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.entitled
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.incoming
import com.paycore.payment.entitlement.domain.SnapshotFixture.leaseAt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * 권리 내용 해석·정규 JSON·contentHash(명세 §3.1, §10). 내용은 MASTER 원본의 계약 모양이며, 모르는 필드·비정규 시각·hash 불일치는 계약 위반이다.
 */
class EntitlementContentSpec : FunSpec({

    test("the canonical JSON re-parses to the same content and the same content hash (round trip)") {
        val content = entitled()
        val parsed = EntitlementContent.fromJson(MiniJson.parse(content.canonical), 1, allowLeaseFields = false)
        // 정규 JSON은 배열을 식별자 순으로 정렬한다 — 목록 순서가 아니라 정규 내용·hash로 비교한다.
        parsed.canonical shouldBe content.canonical
        parsed.contentHash shouldBe content.contentHash
        content.contentHash shouldStartWith "ec1:"
    }

    test("an unknown field, a non-canonical timestamp or an unknown enum value is a contract violation (fail-closed)") {
        val canonical = entitled().canonical
        listOf(
            canonical.replaceFirst("\"merchantId\"", "\"extra\":1,\"merchantId\""),
            canonical.replace(".000Z", "Z"),
            canonical.replaceFirst("\"APPROVED\"", "\"MAYBE\""),
        ).forEach { variant -> shouldThrow<SnapshotContractViolation> { EntitlementContent.fromJson(MiniJson.parse(variant), 1, allowLeaseFields = false) } }
    }

    test("an incoming snapshot whose declared hash differs from the content, or whose aggregate differs, is rejected") {
        val content = entitled()
        shouldThrow<SnapshotContractViolation> {
            IncomingSnapshot("e1", content.merchantId, 1, 1, content, "ec1:" + "0".repeat(64), leaseAt())
        }
        shouldThrow<SnapshotContractViolation> { IncomingSnapshot("e1", content.merchantId, 2, 1, content, content.contentHash, leaseAt()) }
        incoming(content).isSupported shouldBe true
    }

    test("content invariants: revision ≥ 1, at least one MID, unique site ids, one activation per method; lease ends after issuance") {
        val ids = ids()
        val c = entitled(ids)
        listOf<() -> Any>(
            { c.copy(revision = 0) },
            { c.copy(accounts = emptyList()) },
            { c.copy(sites = c.sites + c.sites.first()) },
            { c.copy(accounts = c.accounts.map { it.copy(activations = it.activations + it.activations) }) },
            { Lease(SnapshotFixture.t0, 0, SnapshotFixture.t0) },
            { Interval(SnapshotFixture.t0, SnapshotFixture.t0) },
        ).forEach { shouldThrow<SnapshotContractViolation> { it() } }
    }

    test("the canonical writer sorts keys, writes UTC milliseconds and escapes only quotes, backslashes and control characters") {
        CanonicalJson.write(mapOf("b" to 1L, "a" to listOf(true, null, "x\"y\\z\u0001é"), "c" to java.time.Instant.parse("2026-10-09T01:02:03.400Z"))) shouldBe
            "{\"a\":[true,null,\"x\\\"y\\\\z\\u0001é\"],\"b\":1,\"c\":\"2026-10-09T01:02:03.400Z\"}"
    }
})

/** 시험용 최소 JSON 파서(정규 JSON만 다룬다 — 객체·배열·문자열·정수·true/false/null). domain 해석을 Jackson 없이 시험하려고 쓴다. */
internal object MiniJson {
    fun parse(text: String): JsonValue = Parser(text).run { value().also { check(pos == text.length) { "trailing characters" } } }

    private class Parser(val s: String) {
        var pos = 0

        fun value(): JsonValue = when (s[pos]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonValue.JString(str())
            't' -> literal("true", JsonValue.JBool(true))
            'f' -> literal("false", JsonValue.JBool(false))
            'n' -> literal("null", JsonValue.JNull)
            else -> num()
        }

        fun obj(): JsonValue {
            pos++
            val fields = linkedMapOf<String, JsonValue>()
            if (s[pos] == '}') return JsonValue.JObject(fields).also { pos++ }
            while (true) {
                val k = str()
                check(s[pos++] == ':')
                fields[k] = value()
                if (s[pos++] == '}') return JsonValue.JObject(fields)
            }
        }

        fun arr(): JsonValue {
            pos++
            val items = mutableListOf<JsonValue>()
            if (s[pos] == ']') return JsonValue.JArray(items).also { pos++ }
            while (true) {
                items += value()
                if (s[pos++] == ']') return JsonValue.JArray(items)
            }
        }

        fun str(): String {
            check(s[pos++] == '"')
            val out = StringBuilder()
            while (s[pos] != '"') {
                val c = s[pos++]
                if (c == '\\') {
                    when (val e = s[pos++]) {
                        'u' -> out.append(s.substring(pos, pos + 4).toInt(16).toChar()).also { pos += 4 }
                        else -> out.append(e)
                    }
                } else {
                    out.append(c)
                }
            }
            pos++
            return out.toString()
        }

        fun num(): JsonValue {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            return JsonValue.JNumber(s.substring(start, pos).toLong())
        }

        fun literal(word: String, v: JsonValue): JsonValue {
            check(s.startsWith(word, pos))
            pos += word.length
            return v
        }
    }
}
