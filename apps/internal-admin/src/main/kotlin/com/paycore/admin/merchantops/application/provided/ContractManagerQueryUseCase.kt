package com.paycore.admin.merchantops.application.provided

/**
 * 계약 담당자 후보 목록(MERCHANT-FLOW-001 FLOW-2d — D-GOV-024 Q-FLOW-39): 사업자 계약 접수의 담당자로 고를 수 있는 **활성 OPERATOR**의 표시명. 조회자는 활성 OPERATOR만
 * (아니면 null — 웹 403). 본인은 [ContractManagerCandidate.self]로 표시하고 목록이 잘려도 반드시 넣는다. 로그인 이름·이메일·다른 역할은 없다.
 * [ContractManagerCandidate.actorId]는 웹 adapter가 세션 대응표의 불투명 참조로 바꾸는 데만 쓴다(응답·DOM에 계정 키 없음 — Q-FLOW-40).
 */
interface ContractManagerQueryUseCase {
    /**
     * 재리뷰 MAJOR-1(Coordinator 결정 "예 1"): [target]을 주면 그 접수 건에 **저장된 담당자 계정 키**와 같은 후보에 [ContractManagerCandidate.current] = true(표시명 비교 없음).
     * 저장된 담당자가 목록에 없으면(비활성·OPERATOR 아님) 아무도 current가 아니다. [target]이 없으면 current = null(현행).
     */
    fun candidates(viewerActorId: String, target: ContractManagerTarget? = null): ContractManagerCandidates?
}

/** 항목 수정 대상 접수 건(경로·질의 값 — 형식은 웹 adapter가 검사). */
data class ContractManagerTarget(val businessId: String, val intakeSeq: Int)

data class ContractManagerCandidates(val candidates: List<ContractManagerCandidate>, val truncated: Boolean)

data class ContractManagerCandidate(val actorId: String, val displayName: String, val self: Boolean, val current: Boolean? = null) {
    override fun toString(): String = "ContractManagerCandidate(self=$self, ***)"
}
