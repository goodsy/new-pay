package com.paycore.admin.account.application.provided

/**
 * 활성 OPERATOR 계정 목록(MERCHANT-FLOW-001 FLOW-2d — 사업자 계약 접수의 계약 담당자 고르기, D-GOV-024 Q-FLOW-39). **표시명과 계정 키만** 돌려준다 —
 * 로그인 이름(D-ADMIN1-017)·이메일·다른 역할·상태는 읽지도 돌려주지도 않는다. 조회 권한(OPERATOR만)은 호출하는 업무 Use Case가 판정한다.
 * 최신 계정부터(계정 키 ULID 내림차순 — 새로 만든 담당자가 앞) 최대 [ActiveOperatorPage.LIMIT]명, 더 있으면 [ActiveOperatorPage.truncated].
 */
interface ActiveOperatorQuery {
    fun activeOperators(): ActiveOperatorPage
}

data class ActiveOperatorPage(val operators: List<ActiveOperatorView>, val truncated: Boolean) {
    companion object {
        const val LIMIT = 100
    }
}

/** 활성 OPERATOR 한 명(계정 키는 서버 안에서만 — 화면에는 불투명 참조로 바꿔 보낸다). */
data class ActiveOperatorView(val actorId: String, val displayName: String)
