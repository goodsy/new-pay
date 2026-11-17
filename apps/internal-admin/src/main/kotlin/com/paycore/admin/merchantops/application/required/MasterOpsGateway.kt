package com.paycore.admin.merchantops.application.required

import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria

/**
 * MASTER 내부 조회 API 호출 Port(MERCHANT-OPS-001 §4 — 구현 `adapter.integration.HttpMasterOpsGateway`: 서명·전용 permit·timeout·응답 상한·허용 필드 모양 검사).
 * **DB TX 안에서 부르면 `IllegalStateException`**. 경로 변수·본문 값은 호출자가 형식 검사한 값만 넣는다. 실패는 예외가 아니라 [MasterCallResult.Unavailable].
 */
interface MasterOpsGateway {
    fun search(criteria: MerchantSearchCriteria): MasterCallResult

    fun detail(merchantId: String): MasterCallResult

    fun history(merchantId: String, afterSeq: Long): MasterCallResult

    /**
     * MASTER 등록 명령(MERCHANT-OPS-002 §4.1 — 같은 서명기·키, D-MOPS-005). 조회와 **별개의 쓰기 permit**(대기 0), 매 호출 새 `t`, 응답 상한 4 KiB, 허용 모양 fail-closed.
     * 결과는 값([MasterCommandResult]) — timeout·연결 끊김·해석 불가는 [MasterCommandResult.Unknown](효과 단정 없음). **DB TX 안에서 부르면 `IllegalStateException`**.
     */
    fun command(command: MasterCommand): MasterCommandResult

    /**
     * 2인 승인 실행([deadline] false — `/<operation.path>`) 또는 기한 확인([deadline] true — `/approval-deadline-confirmations`): 저장 바이트 [body] 그대로 POST(MERCHANT-OPS-002 §9.4·§9.5).
     * 쓰기 permit(등록 명령과 공유 — 대기 0), 매 호출 새 `t`, 응답 4 KiB, 응답 매핑 C-28. **DB TX 안에서 부르면 `IllegalStateException`**.
     */
    fun approval(operation: MerchantApprovalOperation, body: ByteArray, deadline: Boolean): MerchantApprovalResult

    /** 결정 조회 `/approval-decisions`(조회 permit — 쓰기 효과 없음). 본문 `{"approvalId","correlationId":"mop.<approvalId>"}`. */
    fun approvalDecision(approvalId: String): MerchantApprovalResult
}

sealed interface MasterCallResult {
    data class Found(val document: Map<String, Any?>) : MasterCallResult {
        /** BUSINESS-PROFILE-001 BIZ-3 리뷰 NIT-5: 사업자 상세 문서(개인정보 원문)도 이 값으로 온다 — toString은 값을 가린다. */
        override fun toString(): String = "Found(***)"
    }

    data object NotFound : MasterCallResult

    data class Unavailable(val reason: String) : MasterCallResult
}
