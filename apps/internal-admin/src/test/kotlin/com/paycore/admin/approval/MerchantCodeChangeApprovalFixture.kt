package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestMerchantCodeChange
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply

/**
 * MERCHANT-CODE-001 MC-1b 코드 변경 승인 Named Fixture(요청·승인 Use Case 경로로만 — 직접 INSERT 없음). 승인·결정 조회·기한 확인 응답 표는 [base]를 공유하고 `merchant-code-changes`
 * 실행 경로를 같은 표에 연결한다. Spring bean이 아니다. 명시 값(Demo): 현재 코드 [CURRENT](계약 승인 전 — `merchantCodeChangeable = true`), 새 코드 [NEW], 사유 `TYPO_CORRECTION`,
 * 근거 참조 [EVIDENCE](가맹점 요청 문서 모양).
 */
class MerchantCodeChangeApprovalFixture(
    val base: MerchantOpsApprovalFixture,
    private val merchants: MerchantOpsApprovalUseCase,
    private val path: ApprovalPath,
    private val storage: AdminAccountStorage,
) {
    fun install() {
        base.install()
        StubMasterOpsServer.replyWith(StubMasterOpsServer.MERCHANT_CODE_CHANGES) { body ->
            base.executeReplies[base.idOf(body)]?.invoke(body) ?: Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")
        }
    }

    fun uninstall() {
        base.uninstall()
        StubMasterOpsServer.clearDynamic(StubMasterOpsServer.MERCHANT_CODE_CHANGES)
    }

    /** MASTER 상세: 변경 가능(기본 — 계약 승인 전·변경 0회) 또는 [changeable] false(계약 승인 뒤), [history]로 이미 변경한 상태. */
    fun detail(merchantId: String, code: String = CURRENT, changeable: Boolean = true, history: String? = null) {
        StubMasterOpsServer.reply(
            StubMasterOpsServer.merchantPath(merchantId),
            Reply(200, StubMasterOpsServer.detailJson(merchantId, merchantCode = code, codeChangeable = changeable, codeHistory = history)),
        )
    }

    /** 변경 실행 뒤 MASTER 상세(현재 코드 [NEW], 은퇴 [CURRENT], 변경 불가). */
    fun changedDetail(merchantId: String) = detail(merchantId, NEW, changeable = false, history = StubMasterOpsServer.changedHistoryJson(CURRENT, NEW))

    fun request(operator: AdminAccount, merchantId: String, newCode: String = NEW, reason: String = "TYPO_CORRECTION", evidence: String = EVIDENCE): ApprovalOutcome =
        merchants.requestMerchantCodeChange(
            RequestMerchantCodeChange(operator.id.value, storage.load(operator.id).sessionGeneration, merchantId, newCode, reason, evidence, path.context()),
        )

    /** 적격 코드 변경 요청 — (승인 ID, 가맹점 ID). */
    fun requested(operator: AdminAccount = base.operator(), merchantId: String = base.newMerchantId()): Pair<String, String> {
        detail(merchantId)
        val outcome = request(operator, merchantId)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture code change request failed: $outcome")).approvalId to merchantId
    }

    companion object {
        const val CURRENT = "shopabcd12"
        const val NEW = "shopnew2026"
        const val EVIDENCE = "MERCHANT-REQ/2026-0001"
        const val DETAIL = "from=$CURRENT;to=$NEW;reason=TYPO_CORRECTION"
    }
}
