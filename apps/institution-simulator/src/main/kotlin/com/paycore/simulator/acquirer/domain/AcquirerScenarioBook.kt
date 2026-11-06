package com.paycore.simulator.acquirer.domain

import java.time.Duration

/** 한 매입사의 시나리오 규칙 목록(설정 순서 — 같은 종류는 처음 맞는 규칙 하나만 적용). */
class AcquirerScenarioBook(val rules: List<AcquirerScenarioRule>) {

    /** 레코드에 맞는 첫 레코드 시나리오(없으면 null). */
    fun forRecord(file: AcquisitionFileRef, record: ReceivedRecord): AcquirerScenarioRule? = rules.firstOrNull { it.matchesRecord(file, record) }

    fun fileRejected(file: AcquisitionFileRef): Boolean = fileRule(AcquirerScenarioAction.FILE_REJECT, file) != null

    fun ackDropped(file: AcquisitionFileRef): Boolean = fileRule(AcquirerScenarioAction.DROP_ACK, file) != null

    fun ackDelay(file: AcquisitionFileRef): Duration = fileRule(AcquirerScenarioAction.DELAY_ACK, file)?.delay ?: Duration.ZERO

    /** SFTP 장애 주입 규칙(파일 ID로). */
    fun sftpRule(action: AcquirerScenarioAction, file: AcquisitionFileRef): AcquirerScenarioRule? {
        require(action.scope == AcquirerScenarioAction.Scope.SFTP) { "not an SFTP scenario" }
        return fileRule(action, file)
    }

    private fun fileRule(action: AcquirerScenarioAction, file: AcquisitionFileRef): AcquirerScenarioRule? =
        rules.firstOrNull { it.action == action && it.fileId == file.value }

    override fun toString(): String = "AcquirerScenarioBook(${rules.map { it.action }})"

    companion object {
        val NONE = AcquirerScenarioBook(emptyList())
    }
}
