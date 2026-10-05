// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.batchadapter.ok.sample.adapter.batch

import com.paycore.archfixture.batchadapter.ok.sample.application.provided.RunSettlement
import org.springframework.batch.core.BatchStatus

/** Batch 배선은 application.provided use case만 부른다. */
class SettlementStep(private val runSettlement: RunSettlement) {
    fun execute(day: String): BatchStatus =
        if (runSettlement.run(day) >= 0) BatchStatus.COMPLETED else BatchStatus.FAILED
}
