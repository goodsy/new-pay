package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.EntitlementTiming
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Duration
import java.util.Base64

/**
 * 권리 Snapshot 발행·전달 설정(MASTER-SNAPSHOT-001 명세 §8). **운영 기본값이 없다**: 모든 값이 필수이며 하나라도 없거나 범위 밖이면 기동이
 * 실패한다(허용 §4.2 마지막 문단 — 개발 fixture를 운영 기본값으로 쓰지 않는다). 시험·로컬 Simulator는 Demo 값을 명시적으로 넣는다.
 *
 * 서명 키([Dispatch.signingKey], Base64, 32바이트 이상)는 환경별 비밀 저장소에서 주입한다(D-MS-004). 저장소·설정 파일에 운영 키를 두지 않는다.
 * 운영망 mTLS 구성은 LIVE 허용 전 별도 작업이다(D-MS-004 — 이번 범위 밖).
 */
@ConfigurationProperties(prefix = "paycore.master.entitlement")
class EntitlementSettings(
    override val leaseDuration: Duration,
    override val renewBefore: Duration,
    val relay: Relay,
    val dispatch: Dispatch,
) : EntitlementTiming {

    class Relay(
        val batchSize: Int,
        val claimLease: Duration,
        val backoffInitial: Duration,
        val backoffMax: Duration,
        val backoffJitterRatio: Double,
    )

    class Dispatch(
        val endpoint: URI,
        val connectTimeout: Duration,
        val readTimeout: Duration,
        val signingKeyId: String,
        val signingKey: String,
    ) {
        /** Base64 해독한 서명 키(32바이트 이상). */
        val signingKeyBytes: ByteArray = try {
            Base64.getDecoder().decode(signingKey)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("paycore.master.entitlement.dispatch.signing-key must be Base64", e)
        }

        override fun toString(): String = "Dispatch(endpoint=$endpoint, signingKeyId=$signingKeyId, signingKey=****)"
    }

    override val relayBatchSize: Int get() = relay.batchSize
    override val claimLease: Duration get() = relay.claimLease
    override val backoffInitial: Duration get() = relay.backoffInitial
    override val backoffMax: Duration get() = relay.backoffMax
    override val backoffJitterRatio: Double get() = relay.backoffJitterRatio

    init {
        require(leaseDuration.isPositive) { "lease-duration must be positive" }
        require(renewBefore.isPositive && renewBefore < leaseDuration) { "renew-before must be positive and shorter than lease-duration" }
        require(relay.batchSize in 1..MAX_BATCH) { "relay.batch-size must be within 1..$MAX_BATCH" }
        require(relay.claimLease.isPositive) { "relay.claim-lease must be positive" }
        require(relay.backoffInitial.isPositive && relay.backoffMax >= relay.backoffInitial) { "relay backoff must be positive and max >= initial" }
        require(relay.backoffJitterRatio >= 0.0 && relay.backoffJitterRatio < 1.0) { "relay.backoff-jitter-ratio must be within [0, 1)" }
        require(dispatch.endpoint.scheme in setOf("http", "https") && dispatch.endpoint.host != null) { "dispatch.endpoint must be an http(s) URL" }
        require(dispatch.connectTimeout.isPositive && dispatch.readTimeout.isPositive) { "dispatch timeouts must be positive" }
        require(KEY_ID.matches(dispatch.signingKeyId)) { "dispatch.signing-key-id must be 1..32 characters of [A-Za-z0-9._-]" }
        require(dispatch.signingKeyBytes.size >= MIN_KEY_BYTES) { "dispatch.signing-key must decode to at least $MIN_KEY_BYTES bytes" }
    }

    override fun toString(): String = "EntitlementSettings(leaseDuration=$leaseDuration, renewBefore=$renewBefore, dispatch=$dispatch)"

    companion object {
        const val MAX_BATCH = 500
        const val MIN_KEY_BYTES = 32
        val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EntitlementSettings::class)
class EntitlementSettingsConfiguration
