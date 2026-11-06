package com.paycore.simulator.acquirer.domain

import java.time.Duration

/** 시나리오 규칙 하나. 모양 위반은 설정 오류(기동 실패). */
data class AcquirerScenarioRule(
    val action: AcquirerScenarioAction,
    val fileId: String? = null,
    val transactionId: String? = null,
    val approvedAmountMinor: Long? = null,
    val reason: String? = null,
    val delay: Duration? = null,
    val times: Int? = null,
) {
    init {
        fileId?.let { require(AcquisitionFileRef.parseOrNull(it) != null) { "scenario file-id must be an acquisition file id" } }
        transactionId?.let { require(ID.matches(it)) { "scenario transaction-id must be 1..64 of [A-Za-z0-9._:#-]" } }
        approvedAmountMinor?.let { require(it >= 1) { "scenario approved-amount-minor must be positive" } }
        when (action.scope) {
            AcquirerScenarioAction.Scope.RECORD -> require((transactionId == null) != (approvedAmountMinor == null)) {
                "record scenario $action needs exactly one of transaction-id or approved-amount-minor"
            }
            else -> require(fileId != null && transactionId == null && approvedAmountMinor == null) { "file/SFTP scenario $action needs file-id only" }
        }
        require((action == AcquirerScenarioAction.REJECT) == (reason != null)) { "only REJECT carries a reason" }
        reason?.let { require(TOKEN.matches(it)) { "scenario reason must be 1..32 of [A-Z0-9_]" } }
        val delayed = action == AcquirerScenarioAction.DELAY_RESULT || action == AcquirerScenarioAction.DELAY_ACK
        require(delayed == (delay != null)) { "only DELAY_RESULT and DELAY_ACK carry a delay" }
        delay?.let { require(!it.isNegative && !it.isZero && it <= MAX_DELAY && it.nano % NANOS_PER_MILLI == 0) { "scenario delay must be whole ms within (0, $MAX_DELAY]" } }
        require((action.scope == AcquirerScenarioAction.Scope.SFTP) == (times != null)) { "only SFTP scenarios carry times" }
        times?.let { require(it in 1..MAX_TIMES) { "scenario times must be within 1..$MAX_TIMES" } }
    }

    fun matchesRecord(file: AcquisitionFileRef, record: ReceivedRecord): Boolean =
        action.scope == AcquirerScenarioAction.Scope.RECORD && (fileId == null || fileId == file.value) &&
            (transactionId == null || transactionId == record.transactionId) && (approvedAmountMinor == null || approvedAmountMinor == record.approvedAmountMinor)

    private companion object {
        const val MAX_TIMES = 100
        const val NANOS_PER_MILLI = 1_000_000
        val MAX_DELAY: Duration = Duration.ofDays(7)
        val ID = Regex("^[A-Za-z0-9._:#-]{1,64}$")
        val TOKEN = Regex("^[A-Z0-9_]{1,32}$")
    }
}
