package com.paycore.master.merchant.application

import com.paycore.master.merchant.adapter.integration.HmacRequestFingerprintDigester
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.required.DeploymentModePort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 관리 명령 요청 fingerprint(명세 §3.2 관례 — SEC-BIZ-001 M-2, D-MOPS-010 ②·D-GOV-001 ②): 같은 키·같은 내용 판정의 기준.
 * 현재 형식 `h1:` + HMAC-SHA256(MASTER fingerprint 키, canonical) 소문자 hex — canonical(연산 코드·필드 길이 접두 연결) **전체**를 비밀 키로 감싼다(개인정보 필드가 무염 해시로 남지 않게).
 * 옛 형식 `m1:` + 무염 SHA-256(canonical)은 Demo 한정 호환 비교에만 쓴다(L-25 제거 조건). 필드 경계·null·연산이 다르면 다른 값이어야 한다
 * (경계를 섞어 다른 요청이 같은 fingerprint가 되면 다른 내용이 저장 결과 재생으로 잘못 수렴한다).
 */
class AdminRequestFingerprintSpec : BehaviorSpec({

    val demo = object : DeploymentModePort {
        override fun isDemo(): Boolean = true
    }
    val keyA = Base64.getEncoder().encodeToString("unit-only-request-fingerprint-key-AAAAAAAAAAAA".toByteArray())
    val keyB = Base64.getEncoder().encodeToString("unit-only-request-fingerprint-key-BBBBBBBBBBBB".toByteArray())
    val digesterA = HmacRequestFingerprintDigester(keyA, emptyList(), demo)
    val digesterB = HmacRequestFingerprintDigester(keyB, emptyList(), demo)

    fun fp(operation: AdminOperation, vararg fields: String?) = AdminCommandExecutor.fingerprintOf(digesterA, operation, fields.toList())
    fun legacy(operation: AdminOperation, vararg fields: String?) = AdminCommandExecutor.legacyFingerprintOf(operation, fields.toList())

    Given("request fingerprints (current h1: keyed format)") {
        Then("the format is 'h1:' + 64 lower-case hex (fits VARCHAR(80)) and the same key + same input gives the same value") {
            fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A") shouldMatch Regex("^h1:[0-9a-f]{64}$")
            fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A").length shouldBeLessThanOrEqual 80
            fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A") shouldBe fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A")
        }

        Then("the value is HMAC-SHA256(key, canonical) of the whole canonical string — independent recomputation with the key matches") {
            val canonical = AdminCommandExecutor.canonicalOf(AdminOperation.REGISTER_BUSINESS, listOf("1234567890", "A"))
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getDecoder().decode(keyA), "HmacSHA256")) }
            fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A") shouldBe "h1:" + java.util.HexFormat.of().formatHex(mac.doFinal(canonical))
        }

        Then("without the key the stored value cannot be matched by a dictionary: another key gives another value, and it never equals the unkeyed m1 value") {
            val input = listOf("1234567890", "홍길동", "01012345678")
            val a = AdminCommandExecutor.fingerprintOf(digesterA, AdminOperation.CREATE_BUSINESS, input)
            val b = AdminCommandExecutor.fingerprintOf(digesterB, AdminOperation.CREATE_BUSINESS, input)
            a shouldNotBe b
            a.removePrefix("h1:") shouldNotBe AdminCommandExecutor.legacyFingerprintOf(AdminOperation.CREATE_BUSINESS, input).removePrefix("m1:")
            input.forEach { a shouldNotContain it }
        }

        Then("field boundaries, absent fields, field order and the operation all change the value") {
            val base = fp(AdminOperation.REGISTER_MERCHANT, "ab", "c")
            listOf(
                fp(AdminOperation.REGISTER_MERCHANT, "a", "bc"),
                fp(AdminOperation.REGISTER_MERCHANT, "ab", "c", null),
                fp(AdminOperation.REGISTER_MERCHANT, "c", "ab"),
                fp(AdminOperation.REGISTER_MERCHANT_SITE, "ab", "c"),
                fp(AdminOperation.REGISTER_MERCHANT, "ab", "c "),
            ).forEach { it shouldNotBe base }
            fp(AdminOperation.REGISTER_MERCHANT, null) shouldNotBe fp(AdminOperation.REGISTER_MERCHANT, "~")
        }
    }

    Given("the legacy m1: format (Demo-only compatibility comparison — L-25)") {
        Then("it is 'm1:' + 64 lower-case hex of the same canonical string, unchanged from before SEC-BIZ-001 (stored rows still compare)") {
            legacy(AdminOperation.REGISTER_BUSINESS, "1234567890", "A") shouldMatch Regex("^m1:[0-9a-f]{64}$")
            // 고정 값: HMAC 전 코드(`9121a50` AdminCommandExecutor.fingerprintOf)가 같은 입력에 낸 값 — 기존 저장 행과 비교가 깨지지 않음을 고정한다.
            legacy(AdminOperation.REGISTER_BUSINESS, "1234567890", "A") shouldBe
                "m1:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest("16:RegisterBusiness|10:1234567890|1:A|".toByteArray(Charsets.UTF_8)),
                )
            legacy(AdminOperation.REGISTER_MERCHANT, "ab", "c") shouldNotBe legacy(AdminOperation.REGISTER_MERCHANT, "a", "bc")
        }
    }
})
