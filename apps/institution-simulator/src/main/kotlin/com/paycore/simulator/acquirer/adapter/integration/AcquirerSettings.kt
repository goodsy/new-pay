package com.paycore.simulator.acquirer.adapter.integration

import com.paycore.simulator.acquirer.application.required.VirtualAcquirer
import com.paycore.simulator.acquirer.application.required.VirtualAcquirers
import com.paycore.simulator.acquirer.domain.AcquirerScenarioAction
import com.paycore.simulator.acquirer.domain.AcquirerScenarioBook
import com.paycore.simulator.acquirer.domain.AcquirerScenarioRule
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration

/**
 * 가상 매입사 설정(`paycore.simulator.acquirer`, ACQUIRING-001 2-E — **Demo, 운영값 아님**). D-ACQ-006: 클라이언트 키 인증 + 서버 host key, 키는 **파일 경로**로만
 * 받는다(설정·로그에 키 원문 없음). 운영 기본값 없음:
 * - 매입사(`acquirers`)가 없으면 0개 — SFTP 서버·Worker를 기동하지 않는다(fail-closed, 포트도 열지 않는다).
 * - 매입사가 있으면 `sftp.bind-host`·`sftp.port`·`sftp.host-key-path`·`root-dir`·`poll-interval`·`ack-delay`·`result-delay`·`part-retention`이 모두 필수이고
 *   형식 밖이면 기동 실패.
 * - host key 개인키 파일은 소유자만 접근할 수 있어야 한다(POSIX — OpenSSH 규칙). 허용 클라이언트 공개키 파일(authorized_keys 형식)은 매입사마다 1~2개 파일이고
 *   그 안 키도 모두 합쳐 1~2개다(교체 중 2개 — 키 개수는 SFTP 서버 기동 때 센다). 공개키 파일과 root-dir은 그룹·기타 쓰기 권한이 없어야 한다(리뷰 m-7 — 남이 허용
 *   키를 더하거나 chroot 디렉터리를 바꿔치울 수 없게).
 * 매입사 하나 = SFTP 계정 하나(username)이고 chroot = `{root-dir}/{code}` 아래 `inbox`(업로드)·`outbox`(ACK·결과).
 */
@ConfigurationProperties(prefix = "paycore.simulator.acquirer")
class AcquirerSettings(
    sftp: Sftp?,
    rootDir: Path?,
    pollInterval: Duration?,
    ackDelay: Duration?,
    resultDelay: Duration?,
    partRetention: Duration?,
    acquirers: List<Acquirer>?,
) : VirtualAcquirers {

    class Sftp(val bindHost: String, val port: Int, val hostKeyPath: Path) {
        override fun toString(): String = "Sftp(bindHost=$bindHost, port=$port)"
    }

    class Scenario(
        val action: AcquirerScenarioAction,
        val fileId: String?,
        val transactionId: String?,
        val approvedAmountMinor: Long?,
        val reason: String?,
        val delay: Duration?,
        val times: Int?,
    ) {
        fun toRule() = AcquirerScenarioRule(action, fileId, transactionId, approvedAmountMinor, reason, delay, times)
    }

    class Acquirer(val code: String, val username: String, val authorizedKeyPaths: List<Path>, val vanClient: String, val scenarios: List<Scenario>?) {
        override fun toString(): String = "Acquirer(code=$code, username=$username, keys=${authorizedKeyPaths.size})"
    }

    private val configuredAcquirers: List<Acquirer> = acquirers.orEmpty()

    /** 매입사가 하나 이상 설정됐는가(아니면 SFTP 서버·Worker를 기동하지 않는다). */
    val configured: Boolean = configuredAcquirers.isNotEmpty()

    private val sftpValue: Sftp? = sftp
    private val rootDirValue: Path? = rootDir
    private val pollIntervalValue: Duration? = pollInterval
    private val ackDelayValue: Duration? = ackDelay
    private val resultDelayValue: Duration? = resultDelay
    private val partRetentionValue: Duration? = partRetention

    override val acquirers: List<VirtualAcquirer>

    init {
        if (configured) validate()
        this.acquirers = configuredAcquirers.map { a -> VirtualAcquirer(a.code, a.vanClient, AcquirerScenarioBook(a.scenarios.orEmpty().map(Scenario::toRule))) }
    }

    val sftp: Sftp get() = checkNotNull(sftpValue) { NOT_CONFIGURED }

    val rootDir: Path get() = checkNotNull(rootDirValue) { NOT_CONFIGURED }

    val pollInterval: Duration get() = checkNotNull(pollIntervalValue) { NOT_CONFIGURED }

    override val ackDelay: Duration get() = checkNotNull(ackDelayValue) { NOT_CONFIGURED }

    override val resultDelay: Duration get() = checkNotNull(resultDelayValue) { NOT_CONFIGURED }

    override val partRetention: Duration get() = checkNotNull(partRetentionValue) { NOT_CONFIGURED }

    /** SFTP 계정 설정(username → 매입사). */
    val accounts: List<Acquirer> get() = configuredAcquirers

    override fun toString(): String = "AcquirerSettings(sftp=$sftpValue, acquirers=$configuredAcquirers)"

    private fun validate() {
        val s = requireNotNull(sftpValue) { "$PREFIX.sftp is required when acquirers are configured" }
        require(HOST.matches(s.bindHost)) { "$PREFIX.sftp.bind-host must be a host name or address" }
        require(s.port in 0..MAX_PORT) { "$PREFIX.sftp.port must be within 0..$MAX_PORT" }
        requireOwnerOnlyFile(s.hostKeyPath, "$PREFIX.sftp.host-key-path")
        val root = requireNotNull(rootDirValue) { "$PREFIX.root-dir is required when acquirers are configured" }
        require(root.isAbsolute && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(root)) {
            "$PREFIX.root-dir must be an absolute path of a writable directory"
        }
        requireNotWritableByOthers(root, "$PREFIX.root-dir")
        val poll = requireNotNull(pollIntervalValue) { "$PREFIX.poll-interval is required when acquirers are configured" }
        require(poll >= MIN_POLL) { "$PREFIX.poll-interval must be at least $MIN_POLL" }
        val retention = requireNotNull(partRetentionValue) { "$PREFIX.part-retention is required when acquirers are configured" }
        require(retention >= MIN_PART_RETENTION && retention <= MAX_DELAY) { "$PREFIX.part-retention must be within $MIN_PART_RETENTION..$MAX_DELAY" }
        listOf("ack-delay" to ackDelayValue, "result-delay" to resultDelayValue).forEach { (name, d) ->
            requireNotNull(d) { "$PREFIX.$name is required when acquirers are configured" }
            require(!d.isNegative && d <= MAX_DELAY && d.nano % NANOS_PER_MILLI == 0) { "$PREFIX.$name must be whole milliseconds within 0..$MAX_DELAY" }
        }
        require(configuredAcquirers.map { it.code }.toSet().size == configuredAcquirers.size) { "$PREFIX.acquirers codes must be unique" }
        require(configuredAcquirers.map { it.username }.toSet().size == configuredAcquirers.size) { "$PREFIX.acquirers usernames must be unique" }
        configuredAcquirers.forEachIndexed { i, a ->
            val at = "$PREFIX.acquirers[$i]"
            require(CODE.matches(a.code)) { "$at.code must be 1..32 of [A-Z0-9]" }
            require(USER.matches(a.username)) { "$at.username must be 1..32 of [A-Za-z0-9._-]" }
            require(CLIENT.matches(a.vanClient)) { "$at.van-client must be 1..64 of [a-z0-9-]" }
            require(a.authorizedKeyPaths.size in 1..2) { "$at.authorized-key-paths must name 1..2 public key files" }
            a.authorizedKeyPaths.forEach { p ->
                require(p.isAbsolute && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(p)) {
                    "$at.authorized-key-paths must be absolute paths of readable regular files"
                }
                requireNotWritableByOthers(p, "$at.authorized-key-paths")
            }
            a.scenarios.orEmpty().forEach(Scenario::toRule) // 모양 위반은 여기서 기동 실패
        }
    }

    private fun requireNotWritableByOthers(path: Path, name: String) {
        runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()?.let { perms ->
            require(PosixFilePermission.GROUP_WRITE !in perms && PosixFilePermission.OTHERS_WRITE !in perms) { "$name must not be writable by group or others" }
        }
    }

    private fun requireOwnerOnlyFile(path: Path, name: String) {
        require(path.isAbsolute && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path)) {
            "$name must be an absolute path of a readable regular file"
        }
        // 개인키 파일은 소유자만 읽을 수 있어야 한다(OpenSSH 규칙). POSIX가 아닌 파일 시스템은 배포 환경 검사에 맡긴다.
        runCatching { Files.getPosixFilePermissions(path) }.getOrNull()?.let { perms ->
            require(perms.none { it in GROUP_OR_OTHER }) { "$name must not be accessible by group or others" }
        }
    }

    private companion object {
        const val PREFIX = "paycore.simulator.acquirer"
        const val NOT_CONFIGURED = "no virtual acquirer is configured"
        const val MAX_PORT = 65_535
        const val NANOS_PER_MILLI = 1_000_000
        val MIN_POLL: Duration = Duration.ofMillis(100)
        val MIN_PART_RETENTION: Duration = Duration.ofMinutes(1)
        val MAX_DELAY: Duration = Duration.ofDays(7)
        val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        val CODE = Regex("^[A-Z0-9]{1,32}$")
        val USER = Regex("^[A-Za-z0-9._-]{1,32}$")
        val CLIENT = Regex("^[a-z0-9-]{1,64}$")
        val GROUP_OR_OTHER = setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AcquirerSettings::class)
class AcquirerSettingsConfiguration
