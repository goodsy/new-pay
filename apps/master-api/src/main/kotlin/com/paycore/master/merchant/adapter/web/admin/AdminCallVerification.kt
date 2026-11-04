package com.paycore.master.merchant.adapter.web.admin

/** 서명 검증 결과([AdminCallSignature.verify]). 통과는 사용한 kid만 담는다(서명·헤더 원문 없음). */
sealed interface AdminCallVerification {
    data class Accepted(val keyId: String) : AdminCallVerification

    data class Rejected(val reason: AdminCallRejection) : AdminCallVerification
}
