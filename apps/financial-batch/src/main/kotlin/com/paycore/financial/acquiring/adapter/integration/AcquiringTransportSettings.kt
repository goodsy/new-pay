package com.paycore.financial.acquiring.adapter.integration

import com.paycore.financial.acquiring.application.required.AcquisitionTransportPolicy
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.util.security.SecurityUtils
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.time.Duration

/**
 * 매입사 SFTP 접속 설정(ACQUIRING-001 2-D, D-ACQ-006 — 클라이언트 키 인증 + host key 고정, 키는 **파일 경로**로만 받는다. 경로·지문은 환경변수
 * `SPRING_APPLICATION_JSON` 등 배포 환경이 준다). 운영 기본값 없음: 매입사 항목이 없으면 그 매입사로 전송하지 않는다(fail-closed — `NOT_CONFIGURED`),
 * 항목이 있는데 값이 빠지거나 형식 밖이거나 **개인키를 읽을 수 없으면**(형식 오류·암호 걸린 키·키 0개/2개 이상 — 리뷰 m-1) 기동 실패. 값은 Demo 값이다
 * (운영 키·host key·endpoint·시간 값은 LIVE Gate §14-3·4).
 *
 * lease 식(리뷰 MJ-1 (c)): 한 시도 = 접속 2회(각 TCP 연결 `connect-timeout` + 인증 `connect-timeout` + SFTP 채널 열기 `io-timeout`) + 요청 6회(stat·read·
 * write 닫기·rename·재접속 stat·read — 각 `io-timeout`) + rename 안전 여유(`io-timeout + clock-skew-allowance`). lease는 이 합 + 시계 차이 허용치보다 길어야 한다.
 * 큰 파일의 요청 수는 정적으로 묶지 않는다 — 대신 rename 직전 남은 lease 검사(LEASE_EXHAUSTED)가 안전을 지킨다(느린 시도는 rename하지 않고 끝난다).
 *
 * @param lease 전송 점유 유효 시간(매입사 항목이 있으면 필수).
 * @param clockSkewAllowance 인스턴스 사이 시계 차이 허용치(매입사 항목이 있으면 필수, 0 이상 1분 이하).
 * @param acquirers 매입사 코드(`[A-Z0-9]{1,32}`) → 접속 설정.
 */
@ConfigurationProperties(prefix = "paycore.financial.acquiring.transport")
class AcquiringTransportSettings(lease: Duration?, clockSkewAllowance: Duration?, acquirers: Map<String, Endpoint>?) : AcquisitionTransportPolicy {

    /**
     * @param privateKeyPath 클라이언트 개인키 파일(암호 없는 OpenSSH/PEM 키 하나 — Demo. 소유자만 읽을 수 있어야 한다). 키 원문은 설정에 두지 않는다.
     * @param hostKeyFingerprints 허용 서버 host key SHA-256 지문(`SHA256:` + base64 43자) 1~2개(교체 중에만 2개). 모르는 host key는 연결 거부.
     * @param inboxDir 업로드 디렉터리, [outboxDir] ACK·결과 디렉터리(서버 chroot 안 절대 경로).
     */
    class Endpoint(
        val host: String,
        val port: Int,
        val username: String,
        val privateKeyPath: Path,
        val hostKeyFingerprints: List<String>,
        val inboxDir: String,
        val outboxDir: String,
        val connectTimeout: Duration,
        val ioTimeout: Duration,
    ) {
        override fun toString(): String = "Endpoint(host=$host, port=$port)"
    }

    val endpoints: Map<String, Endpoint> = acquirers ?: emptyMap()

    /** 기동 때 한 번 읽은 클라이언트 키(로그·toString에 나오지 않는다). */
    private val keyPairs: Map<String, KeyPair> = endpoints.mapValues { (code, e) -> validate(code, e) }

    private val skew: Duration? = clockSkewAllowance

    private val leaseValue: Duration? = lease.also { l ->
        if (endpoints.isNotEmpty()) {
            val s = requireNotNull(skew) { "$PREFIX.clock-skew-allowance is required when an acquirer transport is configured" }
            require(!s.isNegative && s <= MAX_SKEW) { "$PREFIX.clock-skew-allowance must be within 0..$MAX_SKEW" }
            requireNotNull(l) { "$PREFIX.lease is required when an acquirer transport is configured" }
            endpoints.forEach { (code, e) ->
                require(l > minimumLease(e, s)) { "$PREFIX.lease must exceed the attempt bound of acquirer $code (see AcquiringTransportSettings)" }
            }
        }
    }

    override val transmissionLease: Duration get() = checkNotNull(leaseValue) { "no acquirer transport is configured" }

    override fun renameSafetyMargin(acquirerCode: String): Duration =
        checkNotNull(endpoints[acquirerCode]) { "no transport for this acquirer" }.ioTimeout.plus(checkNotNull(skew))

    fun endpoint(acquirerCode: String): Endpoint? = endpoints[acquirerCode]

    internal fun keyPair(acquirerCode: String): KeyPair = keyPairs.getValue(acquirerCode)

    override fun toString(): String = "AcquiringTransportSettings(acquirers=${endpoints.keys})"

    private fun validate(code: String, e: Endpoint): KeyPair {
        val at = "$PREFIX.acquirers.$code"
        require(CODE.matches(code)) { "$PREFIX.acquirers keys must be acquirer codes ([A-Z0-9]{1,32})" }
        require(e.host.isNotBlank() && HOST.matches(e.host)) { "$at.host must be a host name or address" }
        require(e.port in 1..MAX_PORT) { "$at.port must be within 1..$MAX_PORT" }
        require(USER.matches(e.username)) { "$at.username must be 1..32 characters of [A-Za-z0-9._-]" }
        require(e.hostKeyFingerprints.size in 1..2 && e.hostKeyFingerprints.all(FINGERPRINT::matches) && e.hostKeyFingerprints.toSet().size == e.hostKeyFingerprints.size) {
            "$at.host-key-fingerprints must be 1..2 distinct SHA256:<base64> fingerprints"
        }
        require(DIR.matches(e.inboxDir) && DIR.matches(e.outboxDir) && ".." !in e.inboxDir && ".." !in e.outboxDir && e.inboxDir != e.outboxDir) {
            "$at.inbox-dir and outbox-dir must be distinct absolute paths of [A-Za-z0-9._/-]"
        }
        require(e.connectTimeout >= MIN_TIMEOUT && e.connectTimeout <= MAX_TIMEOUT && e.ioTimeout >= MIN_TIMEOUT && e.ioTimeout <= MAX_TIMEOUT) {
            "$at.connect-timeout and io-timeout must be within $MIN_TIMEOUT..$MAX_TIMEOUT"
        }
        val key = e.privateKeyPath
        require(key.isAbsolute && Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(key)) {
            "$at.private-key-path must be an absolute path of a readable regular file"
        }
        // 개인키 파일은 소유자만 읽을 수 있어야 한다(OpenSSH와 같은 규칙). 소유자 계정 일치 검사는 배포 환경 몫(명세 §14-13). POSIX가 아닌 파일 시스템은 배포 검사에 맡긴다.
        runCatching { Files.getPosixFilePermissions(key) }.getOrNull()?.let { perms ->
            require(perms.none { it in GROUP_OR_OTHER }) { "$at.private-key-path must not be readable by group or others" }
        }
        // 리뷰 m-1: 키 내용을 기동 때 파싱한다(첫 전송 때 실패하지 않게). 메시지에 키 내용·경로를 넣지 않는다.
        val pairs = try {
            Files.newInputStream(key).use { SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(KEY_RESOURCE), it, null)?.toList().orEmpty() }
        } catch (ex: Exception) {
            throw IllegalArgumentException("$at.private-key-path must hold one unencrypted private key")
        }
        require(pairs.size == 1) { "$at.private-key-path must hold one unencrypted private key" }
        return pairs.single()
    }

    companion object {
        private const val PREFIX = "paycore.financial.acquiring.transport"
        private const val MAX_PORT = 65_535
        private const val REQUESTS_PER_ATTEMPT = 6L
        private const val KEY_RESOURCE = "acquirer-client-key"
        private val MIN_TIMEOUT: Duration = Duration.ofMillis(100)
        private val MAX_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val MAX_SKEW: Duration = Duration.ofMinutes(1)
        private val CODE = Regex("^[A-Z0-9]{1,32}$")
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val USER = Regex("^[A-Za-z0-9._-]{1,32}$")
        private val FINGERPRINT = Regex("^SHA256:[A-Za-z0-9+/]{43}$")
        private val DIR = Regex("^/[A-Za-z0-9._/-]{0,127}$")
        private val GROUP_OR_OTHER = setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )

        /** 한 시도의 시간 상한(클래스 KDoc 식). lease는 이 값보다 길어야 한다. */
        fun minimumLease(e: Endpoint, skew: Duration): Duration {
            val perConnection = e.connectTimeout.multipliedBy(2).plus(e.ioTimeout)
            return perConnection.multipliedBy(2).plus(e.ioTimeout.multipliedBy(REQUESTS_PER_ATTEMPT)).plus(e.ioTimeout).plus(skew).plus(skew)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AcquiringTransportSettings::class)
class AcquiringTransportConfiguration
