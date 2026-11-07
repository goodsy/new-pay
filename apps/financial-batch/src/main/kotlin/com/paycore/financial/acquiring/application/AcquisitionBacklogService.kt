package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.AcquisitionBacklog
import com.paycore.financial.acquiring.application.provided.ReadAcquisitionBacklogUseCase
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionBacklogReader
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

/** [ReadAcquisitionBacklogUseCase] 구현 — DB count는 읽기 TX 하나(JPA native — C-2), 대기 현황은 [AcquisitionWaitingBoard](마지막 봉인 판정). */
@Service
class AcquisitionBacklogService(
    transactionManager: PlatformTransactionManager,
    private val reader: AcquisitionBacklogReader,
    private val board: AcquisitionWaitingBoard,
    private val clock: AcquiringClock,
) : ReadAcquisitionBacklogUseCase {

    private val transactions = AcquiringLocalTransactions(transactionManager, 1)

    override fun backlog(): AcquisitionBacklog {
        val c = transactions.execute({ false }) { reader.read() }
        return AcquisitionBacklog(
            transmissionUnknown = c.transmissionUnknown, transmissionQuarantined = c.transmissionQuarantined, transmissionWithheld = c.transmissionWithheld, uploadFailedRepeated = c.uploadFailedRepeated,
            recordsUnknown = c.recordsUnknown, recordsConflict = c.recordsConflict, responsesQuarantined = c.responsesQuarantined, receiptConflicts = c.receiptConflicts,
            nettingMismatches = c.nettingMismatches, awaitingResults = c.awaitingResults, cancelNotRequiredOriginalConflict = c.cancelNotRequiredOriginalConflict,
            reacquisitionNettingMismatches = c.reacquisitionNettingMismatches,
            waiting = board.waiting(), cancelReconcile = board.cancelReconcile(), observedAt = clock.now(),
        )
    }
}
