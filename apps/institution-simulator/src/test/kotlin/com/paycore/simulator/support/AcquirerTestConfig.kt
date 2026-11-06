package com.paycore.simulator.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair

/**
 * 가상 매입사 시험 환경(ACQUIRING-001 2-E — Demo 값, 운영값 아님). 임시 디렉터리에 시험마다 새 키 파일(host 개인키 rw-------, 허용 공개키)을 만들고 설정 이름은
 * 운영과 같다(`paycore.simulator.acquirer.*`). 포트 0(임의), Worker 주기 PT1H(시험이 runOnce를 직접 부른다), ACK 지연 0, 결과 지연 PT30S(golden producedAt).
 * 매입사 둘: SIMACQA(시나리오 표 [ScenarioFiles]·거래 ID·금액), SIMACQB(다른 계정·키 — chroot 격리 시험).
 */
object AcquirerTestEnvironment {
    val root: Path = Files.createTempDirectory("acq-sim-root")
    private val keys: Path = Files.createTempDirectory("acq-sim-keys")
    val hostKey: KeyPair = AcquirerTestKeys.ecKeyPair()
    val clientA: KeyPair = AcquirerTestKeys.ecKeyPair()
    val clientB: KeyPair = AcquirerTestKeys.ecKeyPair()
    val stranger: KeyPair = AcquirerTestKeys.ecKeyPair()
    val hostKeyFile: Path = AcquirerTestKeys.writePrivateKey(keys, "host_ecdsa", hostKey)
    val authorizedA: Path = AcquirerTestKeys.writeAuthorizedKeys(keys, "a.pub", clientA.public)
    val authorizedB: Path = AcquirerTestKeys.writeAuthorizedKeys(keys, "b.pub", clientB.public)

    const val USER_A = "paycore-a"
    const val USER_B = "paycore-b"
    const val VAN_CLIENT = "pg-demo"

    /** 파일 단위·SFTP 시나리오가 걸린 파일 ID. */
    object ScenarioFiles {
        const val DROP_ACK = "ACQ.SIMACQA.TEST.20261205.001"
        const val DELAY_ACK = "ACQ.SIMACQA.TEST.20261206.001"
        const val FILE_REJECT = "ACQ.SIMACQA.TEST.20261207.001"
        const val DISCONNECT = "ACQ.SIMACQA.TEST.20261208.001"
        const val DROP_RENAME = "ACQ.SIMACQA.TEST.20261209.001"
    }

    const val REJECT_AMOUNT = 13_013L
}

@TestConfiguration(proxyBeanMethods = false)
class AcquirerTestConfig {

    @Bean
    fun acquirerProperties(): DynamicPropertyRegistrar = DynamicPropertyRegistrar { r ->
        val e = AcquirerTestEnvironment
        val p = "paycore.simulator.acquirer"
        r.add("$p.sftp.bind-host") { "127.0.0.1" }
        r.add("$p.sftp.port") { "0" }
        r.add("$p.sftp.host-key-path") { e.hostKeyFile.toString() }
        r.add("$p.root-dir") { e.root.toString() }
        r.add("$p.poll-interval") { "PT1H" }
        r.add("$p.ack-delay") { "PT0S" }
        r.add("$p.result-delay") { "PT30S" }
        r.add("$p.part-retention") { "PT1H" }
        r.add("$p.acquirers[0].code") { "SIMACQA" }
        r.add("$p.acquirers[0].username") { AcquirerTestEnvironment.USER_A }
        r.add("$p.acquirers[0].authorized-key-paths[0]") { e.authorizedA.toString() }
        r.add("$p.acquirers[0].van-client") { AcquirerTestEnvironment.VAN_CLIENT }
        val scenarios = listOf(
            mapOf("action" to "REJECT", "approved-amount-minor" to AcquirerTestEnvironment.REJECT_AMOUNT.toString(), "reason" to "DEMO_REJECTED"),
            mapOf("action" to "WITHHOLD_RESULT", "transaction-id" to "sc-withhold"),
            mapOf("action" to "DELAY_RESULT", "transaction-id" to "sc-delay", "delay" to "PT2H"),
            mapOf("action" to "DUPLICATE_RESULT", "transaction-id" to "sc-dup"),
            mapOf("action" to "CONFLICTING_RESULT", "transaction-id" to "sc-conflict"),
            mapOf("action" to "DROP_ACK", "file-id" to AcquirerTestEnvironment.ScenarioFiles.DROP_ACK),
            mapOf("action" to "DELAY_ACK", "file-id" to AcquirerTestEnvironment.ScenarioFiles.DELAY_ACK, "delay" to "PT1H"),
            mapOf("action" to "FILE_REJECT", "file-id" to AcquirerTestEnvironment.ScenarioFiles.FILE_REJECT),
            mapOf("action" to "DISCONNECT_DURING_UPLOAD", "file-id" to AcquirerTestEnvironment.ScenarioFiles.DISCONNECT, "times" to "1"),
            mapOf("action" to "DROP_RENAME_RESPONSE", "file-id" to AcquirerTestEnvironment.ScenarioFiles.DROP_RENAME, "times" to "1"),
        )
        scenarios.forEachIndexed { i, s -> s.forEach { (k, v) -> r.add("$p.acquirers[0].scenarios[$i].$k") { v } } }
        r.add("$p.acquirers[1].code") { "SIMACQB" }
        r.add("$p.acquirers[1].username") { AcquirerTestEnvironment.USER_B }
        r.add("$p.acquirers[1].authorized-key-paths[0]") { e.authorizedB.toString() }
        r.add("$p.acquirers[1].van-client") { AcquirerTestEnvironment.VAN_CLIENT }
    }
}
