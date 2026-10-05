// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.batch.ok.sample.adapter.batch

import org.springframework.batch.core.BatchStatus

class SettlementJobStatus {
    fun done(): BatchStatus = BatchStatus.COMPLETED
}
