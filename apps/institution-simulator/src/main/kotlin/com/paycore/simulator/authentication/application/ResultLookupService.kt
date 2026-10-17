package com.paycore.simulator.authentication.application

import com.paycore.simulator.authentication.application.provided.LookupAnswer
import com.paycore.simulator.authentication.application.provided.LookupAuthenticationResultUseCase
import com.paycore.simulator.authentication.application.required.AuthenticationTransactionRepository
import com.paycore.simulator.authentication.application.required.LookupLogStore
import com.paycore.simulator.authentication.application.required.SimulatorClock
import com.paycore.simulator.authentication.application.required.SimulatorTimings
import com.paycore.simulator.authentication.domain.LookupBehaviour
import com.paycore.simulator.authentication.domain.RejectionReason
import com.paycore.simulator.authentication.domain.ResultRef
import com.paycore.simulator.authentication.domain.SimulatorRejection
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * 서버 간 결과 조회(§13.4 — 읽기 전용·멱등: 같은 참조 → 같은 본문, outcome만 시각 함수). 다른 client의 결과·모르는 참조는 존재 비노출 NOT_FOUND. 조회마다
 * append-only 기록을 남기고, "한 번만" 카드는 첫 조회만 지연(웹 adapter가 응답 전 대기)·5xx로 답한다.
 */
@Service
class ResultLookupService(
    private val transactions: AuthenticationTransactionRepository,
    private val log: LookupLogStore,
    private val clock: SimulatorClock,
    private val timings: SimulatorTimings,
) : LookupAuthenticationResultUseCase {

    @Transactional
    override fun lookup(clientId: String, resultRef: String): LookupAnswer {
        val ref = runCatching { ResultRef(resultRef) }.getOrNull() ?: throw SimulatorRejection(RejectionReason.NOT_FOUND)
        val t = transactions.findByResultRef(ref)?.takeIf { it.clientId == clientId } ?: throw SimulatorRejection(RejectionReason.NOT_FOUND)
        val now = clock.now()
        val behaviour = t.card?.lookupBehaviour(log.countFor(ref)) ?: LookupBehaviour.NORMAL
        log.append(ref, now, behaviour)
        return when (behaviour) {
            LookupBehaviour.SERVER_ERROR -> LookupAnswer.ServerError
            LookupBehaviour.DELAY -> LookupAnswer.Result(t.lookup(now), timings.lookupDelay)
            LookupBehaviour.NORMAL -> LookupAnswer.Result(t.lookup(now), Duration.ZERO)
        }
    }
}
