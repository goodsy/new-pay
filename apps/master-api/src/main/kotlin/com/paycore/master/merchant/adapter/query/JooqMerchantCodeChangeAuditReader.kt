package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.CONTRACT
import com.paycore.master.jooq.generated.Tables.MASTER_CHANGE_HISTORY
import com.paycore.master.merchant.application.required.MerchantCodeChangeAuditReader
import com.paycore.master.merchant.domain.MerchantId
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * [MerchantCodeChangeAuditReader] jOOQ 구현(MERCHANT-CODE-001 MC-1b-6 — 한 문장, 읽기 전용). 변경 이력 행(`MerchantCodeChanged`, 대상 = 가맹점)보다 **작은 id**의 계약 승인 이력 행
 * (`ContractRevisionApproved`, 대상 계약의 `merchant_id` = 그 가맹점)이 있으면 위반이다. 이력 detail(JSON)은 읽지 않는다 — 대상은 aggregate 열과 계약 표로만 연결한다.
 */
@Component
class JooqMerchantCodeChangeAuditReader(private val dsl: DSLContext) : MerchantCodeChangeAuditReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun changesAfterContractApproval(): List<MerchantId> {
        val change = MASTER_CHANGE_HISTORY.`as`("chg")
        val approval = MASTER_CHANGE_HISTORY.`as`("apv")
        return dsl.selectDistinct(change.AGGREGATE_ID)
            .from(change)
            .where(change.CHANGE_TYPE.eq(CODE_CHANGED).and(change.AGGREGATE_TYPE.eq(MERCHANT)))
            .andExists(
                dsl.selectOne().from(approval).join(CONTRACT).on(CONTRACT.CONTRACT_ID.eq(approval.AGGREGATE_ID))
                    .where(approval.CHANGE_TYPE.eq(REVISION_APPROVED).and(approval.AGGREGATE_TYPE.eq(CONTRACT_AGGREGATE)))
                    .and(CONTRACT.MERCHANT_ID.eq(change.AGGREGATE_ID))
                    .and(approval.ID.lt(change.ID)),
            )
            .orderBy(change.AGGREGATE_ID)
            .fetch { MerchantId(it.value1()) }
    }

    private companion object {
        const val CODE_CHANGED = "MerchantCodeChanged"
        const val REVISION_APPROVED = "ContractRevisionApproved"
        const val MERCHANT = "Merchant"
        const val CONTRACT_AGGREGATE = "Contract"
    }
}
