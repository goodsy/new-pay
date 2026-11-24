package com.paycore.admin.security

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * IP 허용 목록 기동 검사의 모드 판정(ADMIN-DEMO-LOGIN-001 리뷰 MINOR-4·MINOR-2(a)). 모드 문자열은 설정 바인딩(관대 enum 변환)과 같이 대소문자·구분자를 무시한다 —
 * 예전에는 `live`(소문자)면 account 가드는 LIVE로 보지만 이 검사는 건너뛰었다. 모든 주소 허용은 LIVE, 그리고 데모 간편 로그인이 켜진 DEMO에서 거부한다.
 */
class DeploymentModeAllowlistSpec : BehaviorSpec({

    val everyAddress = IpAllowlist.parse(listOf("10.0.0.0/8", "0.0.0.0/0"))
    val everyV6 = IpAllowlist.parse(listOf("::/0"))
    val narrow = IpAllowlist.parse(listOf("127.0.0.1", "100.64.0.0/10"))
    val refusal = listOf("paycore.admin.network.ip-allowlist permits every address")

    Given("the deployment mode normalization") {
        Then("is case- and separator-insensitive like the enum binding; blank is no mode") {
            listOf("LIVE", "live", "Live", " live ", "DEMO", "demo", "de-mo", "", null).map { SecurityConfig.normalizedDeploymentMode(it) } shouldBe
                listOf("LIVE", "LIVE", "LIVE", "LIVE", "DEMO", "DEMO", "DEMO", null, null)
        }
    }

    Given("the allowlist startup check") {
        Then("LIVE in any spelling refuses an every-address list (IPv4 and IPv6)") {
            listOf("LIVE", "live").forEach { raw ->
                val mode = SecurityConfig.normalizedDeploymentMode(raw)
                SecurityConfig.allowlistStartupViolations(mode, false, everyAddress) shouldBe refusal
                SecurityConfig.allowlistStartupViolations(mode, false, everyV6) shouldBe refusal
            }
        }
        Then("DEMO refuses an every-address list only when the demo quick login is on (MINOR-2(a)); a narrow list passes everywhere") {
            SecurityConfig.allowlistStartupViolations("DEMO", true, everyAddress) shouldBe refusal
            SecurityConfig.allowlistStartupViolations("DEMO", false, everyAddress) shouldBe emptyList()
            listOf("LIVE" to true, "LIVE" to false, "DEMO" to true, "DEMO" to false).forEach { (mode, on) ->
                SecurityConfig.allowlistStartupViolations(mode, on, narrow) shouldBe emptyList()
            }
        }
    }
})
