package com.paycore.simulator.van.adapter.integration

import com.paycore.simulator.van.application.required.VanProfiles
import com.paycore.simulator.van.domain.VanProfile
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.Base64

/**
 * 가상 VAN 설정(`paycore.simulator.van`, AUTH-001 §5 — **Demo, 운영값 아님, 운영 기본값 없음**: 값이 없거나 틀리면 기동 실패). 포트 0은 시험용 임시 포트. HMAC 키는
 * base64 원문을 환경변수로만 받는다(저장소에 운영 키 없음). 기관 가맹점 계정은 프로파일 A(무효과 확정)·B(없음)에 연결한다(AU-15).
 */
@ConfigurationProperties(prefix = "paycore.simulator.van")
class VanSettings(
    val port: Int,
    val lateResponseDelay: Duration,
    val fragmentDelay: Duration,
    /** 서버 소켓 유휴 읽기 timeout(검토 #2 n-4 — 아무것도 보내지 않는 연결을 닫는다, Demo). */
    val idleTimeout: Duration,
    keys: List<Key>,
    profiles: Map<String, Profile>,
    accounts: List<Account>,
) : VanProfiles {

    class Key(val id: String, val client: String, keyBase64: String) {
        val secret: ByteArray = runCatching { Base64.getDecoder().decode(keyBase64) }.getOrElse { throw IllegalArgumentException("van key must be base64") }

        override fun toString(): String = "Key(id=$id, client=$client)"
    }

    class Profile(
        val confirmsNoEffect: Boolean,
        val closableAfter: Duration,
        val inquiryRetention: Duration,
        val creditInstallmentMonths: Set<Int>,
        val pendingDelay: Duration,
    )

    class Account(val account: String, val profile: String)

    val keyRing: VanKeyRing
    private val profileByAccount: Map<String, VanProfile>

    init {
        require(port in 0..MAX_PORT) { "van port must be 0..65535" }
        require(!lateResponseDelay.isNegative && !fragmentDelay.isNegative) { "van delays must not be negative" }
        require(!idleTimeout.isNegative && !idleTimeout.isZero) { "van idle timeout must be positive" }
        require(keys.isNotEmpty() && keys.map { it.id }.toSet().size == keys.size) { "van keys must be non-empty with unique ids" }
        require(keys.all { CLIENT.matches(it.client) }) { "van key client must be a client id" }
        keyRing = VanKeyRing(keys.associate { it.id to VanKeyRing.Key(it.client, it.secret) })
        val built = profiles.mapValues { (name, p) ->
            VanProfile(name, p.confirmsNoEffect, p.closableAfter, p.inquiryRetention, p.creditInstallmentMonths, p.pendingDelay)
        }
        require(accounts.isNotEmpty() && accounts.map { it.account }.toSet().size == accounts.size) { "van accounts must be non-empty and unique" }
        require(accounts.all { ACCOUNT.matches(it.account) && it.profile in built }) { "every van account names a configured profile" }
        profileByAccount = accounts.associate { it.account to built.getValue(it.profile) }
    }

    override fun profileOf(institutionAccount: String): VanProfile? = profileByAccount[institutionAccount]

    override fun toString(): String = "VanSettings(port=$port, keyRing=$keyRing, accounts=${profileByAccount.mapValues { it.value.name }})"

    private companion object {
        const val MAX_PORT = 65_535
        val CLIENT = Regex("^[a-z0-9-]{1,64}$")
        val ACCOUNT = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VanSettings::class)
class VanSettingsConfiguration
