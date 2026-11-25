package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.DeploymentModePort
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import java.util.Base64

/**
 * 요청 지문 HMAC 키 설정·기동 검사(SEC-BIZ-001 M-2 — D-MOPS-010 ②, MERCHANT-OPS-002 C-46·L-23): `paycore.master.admin-channel.request-fingerprint-key`
 * (Base64, 32바이트 이상) — **기본값 없음**(운영 application.yml에 값 없음, 없으면 기동 실패), 서명 키(admin-channel `signing-keys`·권리 dispatch)와 같은 바이트면 기동 실패,
 * LIVE(`isDemo()=false`)에서 저장소의 알려진 Demo 키면 기동 거부. 실패 메시지·`toString`에 키 원문이 없다.
 */
class RequestFingerprintKeySpec : FunSpec({

    val testKey = testConfigFingerprintKey()
    val adminSigning = "dGVzdC1vbmx5LW1hc3Rlci1hZG1pbi1jaGFubmVsLWtleS0wMDAx"
    val dispatchSigning = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh"
    val otherKey = Base64.getEncoder().encodeToString("an-operator-supplied-fingerprint-key-not-in-repo".toByteArray())
    fun mode(demo: Boolean) = object : DeploymentModePort {
        override fun isDemo(): Boolean = demo
    }

    fun runner(demo: Boolean) = ApplicationContextRunner()
        .withUserConfiguration(RequestFingerprintConfiguration::class.java)
        .withBean(DeploymentModePort::class.java, { mode(demo) })

    test("the production application.yml has no request-fingerprint-key value (no production default)") {
        val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).single()
        yaml.getProperty("paycore.master.admin-channel.request-fingerprint-key") shouldBe null
    }

    test("a missing key fails start-up") {
        runner(demo = true).run { ctx ->
            ctx.startupFailure shouldNotBe null
            // 원인 = 설정 바인딩 실패(그 설정 클래스) — 키가 없으니 값 노출 여지도 없다.
            chainOf(ctx.startupFailure) shouldContain "RequestFingerprintSettings"
        }
    }

    test("a valid key starts (Demo with the repository test key; LIVE with another key) and digests to 32 bytes") {
        runner(demo = true).withPropertyValues("paycore.master.admin-channel.request-fingerprint-key=$testKey").run {
            it.startupFailure shouldBe null
            it.getBean(RequestFingerprintDigester::class.java).digest("x".toByteArray()).size shouldBe 32
        }
        runner(demo = false).withPropertyValues("paycore.master.admin-channel.request-fingerprint-key=$otherKey").run { it.startupFailure shouldBe null }
    }

    test("not Base64, shorter than 32 bytes, equal to an admin-channel signing key or the entitlement dispatch key → start-up fails; the message has no key value") {
        val short = Base64.getEncoder().encodeToString(ByteArray(31) { 7 })
        val base64Rule = "paycore.master.admin-channel.request-fingerprint-key must be Base64"
        val lengthRule = "paycore.master.admin-channel.request-fingerprint-key must decode to at least 32 bytes"
        val separationRule = "paycore.master.admin-channel.request-fingerprint-key must differ from every MASTER signing key (separate key spaces)"
        val cases = listOf(
            base64Rule to arrayOf("paycore.master.admin-channel.request-fingerprint-key=not base64 !"),
            lengthRule to arrayOf("paycore.master.admin-channel.request-fingerprint-key=$short"),
            separationRule to arrayOf(
                "paycore.master.admin-channel.request-fingerprint-key=$adminSigning",
                "paycore.master.admin-channel.signing-keys[0].key-id=mops-test-k1", "paycore.master.admin-channel.signing-keys[0].secret=$adminSigning",
            ),
            // 교체 중 두 번째 서명 키와 같아도 거부.
            separationRule to arrayOf(
                "paycore.master.admin-channel.request-fingerprint-key=$otherKey",
                "paycore.master.admin-channel.signing-keys[0].key-id=mops-test-k1", "paycore.master.admin-channel.signing-keys[0].secret=$adminSigning",
                "paycore.master.admin-channel.signing-keys[1].key-id=mops-test-k2", "paycore.master.admin-channel.signing-keys[1].secret=$otherKey",
            ),
            separationRule to arrayOf("paycore.master.admin-channel.request-fingerprint-key=$dispatchSigning", "paycore.master.entitlement.dispatch.signing-key=$dispatchSigning"),
        )
        cases.forEach { (rule, props) ->
            runner(demo = true).withPropertyValues(*props).run { ctx ->
                val failure = ctx.startupFailure
                failure shouldNotBe null
                val chain = chainOf(failure)
                chain shouldContain rule
                listOf("not base64", short, adminSigning, otherKey, dispatchSigning).forEach { chain shouldNotContain it }
            }
        }
    }

    test("LIVE refuses the repository's known Demo key (the test configuration value); Demo accepts it") {
        runner(demo = false).withPropertyValues("paycore.master.admin-channel.request-fingerprint-key=$testKey").run { ctx ->
            ctx.startupFailure shouldNotBe null
            chainOf(ctx.startupFailure) shouldContain "LIVE deployment must not use a Demo paycore.master.admin-channel.request-fingerprint-key"
            chainOf(ctx.startupFailure) shouldNotContain testKey
        }
        shouldThrow<IllegalStateException> { HmacRequestFingerprintDigester(testKey, emptyList(), mode(false)) }.message shouldBe
            "LIVE deployment must not use a Demo paycore.master.admin-channel.request-fingerprint-key"
        HmacRequestFingerprintDigester(testKey, emptyList(), mode(true))
    }

    test("legacy m1: comparison is allowed only while the deployment is Demo (read on each call)") {
        var demo = true
        val switching = object : DeploymentModePort {
            override fun isDemo(): Boolean = demo
        }
        val digester = HmacRequestFingerprintDigester(otherKey, emptyList(), switching)
        digester.legacyComparisonAllowed() shouldBe true
        demo = false
        digester.legacyComparisonAllowed() shouldBe false
    }

    test("toString of the settings and the digester never shows the key") {
        RequestFingerprintSettings(testKey).toString() shouldNotContain testKey
        HmacRequestFingerprintDigester(otherKey, emptyList(), mode(true)).toString() shouldNotContain otherKey
    }
})

/** 기동 실패 원인 사슬의 메시지 전부(" | " 연결). */
private fun chainOf(failure: Throwable?): String = generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

/** 시험 설정(src/test/resources/config/application.yml)의 fingerprint 키 — 저장소에 있는 Demo/시험 값(운영값 아님). */
private fun testConfigFingerprintKey(): String =
    YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("config/application.yml")) }.`object`!!
        .getProperty("paycore.master.admin-channel.request-fingerprint-key")!!
