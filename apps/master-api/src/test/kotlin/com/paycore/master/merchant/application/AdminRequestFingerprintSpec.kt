package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminOperation
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

/**
 * 관리 명령 요청 fingerprint `m1:`(관례, 명세 §3.2): 같은 키·같은 내용 판정의 기준. 필드 경계·null·연산이 다르면 다른 값이어야 한다
 * (경계를 섞어 다른 요청이 같은 fingerprint가 되면 다른 내용이 저장 결과 재생으로 잘못 수렴한다).
 */
class AdminRequestFingerprintSpec : BehaviorSpec({

    fun fp(operation: AdminOperation, vararg fields: String?) = AdminCommandExecutor.fingerprintOf(operation, fields.toList())

    Given("request fingerprints") {
        Then("the format is 'm1:' + 64 lower-case hex and the same input gives the same value") {
            fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A") shouldMatch Regex("^m1:[0-9a-f]{64}$")
            fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A") shouldBe fp(AdminOperation.REGISTER_BUSINESS, "1234567890", "A")
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
})
