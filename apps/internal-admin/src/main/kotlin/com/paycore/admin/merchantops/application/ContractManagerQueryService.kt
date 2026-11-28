package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.ActiveOperatorQuery
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.merchantops.application.provided.ContractManagerCandidate
import com.paycore.admin.merchantops.application.provided.ContractManagerCandidates
import com.paycore.admin.merchantops.application.provided.ContractManagerQueryUseCase
import com.paycore.admin.merchantops.application.provided.ContractManagerTarget
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import org.springframework.stereotype.Service

/**
 * [ContractManagerQueryUseCase](FLOW-2d — D-GOV-024). 조회자 판정 = 계정 현재 행(ACTIVE + OPERATOR — [BusinessPermissions.CONTRACT_INTAKE]와 같은 역할). 목록은 계정 모듈의
 * [ActiveOperatorQuery](표시명·계정 키만). 목록이 잘려 본인이 빠진 경우에만 본인을 맨 앞에 넣는다. 대상(사업자·접수 건)을 주면 저장된 담당자와 계정 키가 같은 후보에 current를 붙인다(잘린 활성 담당자는 끝에 추가). 감사는 남기지 않는다(표시명은 이미 섹션·이력에 보이는 값 — [구현 관례]).
 */
@Service
class ContractManagerQueryService(
    private val sessions: AdminSessionQuery,
    private val operators: ActiveOperatorQuery,
    private val gateway: MasterBusinessGateway,
) : ContractManagerQueryUseCase {

    override fun candidates(viewerActorId: String, target: ContractManagerTarget?): ContractManagerCandidates? {
        val viewer = sessions.current(viewerActorId) ?: return null
        if (!viewer.isActive || viewer.roles.none { it in BusinessPermissions.CONTRACT_INTAKE }) return null
        val page = operators.activeOperators()
        val listed = page.operators.map { ContractManagerCandidate(it.actorId, it.displayName, it.actorId == viewer.actorId) }
        // 잘려서 본인이 빠진 경우에만 본인을 더한다(맨 앞).
        val withSelf = if (listed.any { it.self }) listed else listOf(ContractManagerCandidate(viewer.actorId, viewer.displayName, true)) + listed
        if (target == null) return ContractManagerCandidates(withSelf, page.truncated)
        val stored = storedManager(target)
        // 저장된 담당자가 활성 OPERATOR인데 잘려서 빠졌으면 더한다(끝). 비활성·OPERATOR 아님이면 더하지 않는다 — 같은 표시명의 다른 운영자에게 current가 붙지 않는다(재리뷰 MAJOR-1).
        val withStored = if (stored == null || withSelf.any { it.actorId == stored }) {
            withSelf
        } else {
            sessions.current(stored)?.takeIf { it.isActive && OPERATOR in it.roles }?.let { withSelf + ContractManagerCandidate(it.actorId, it.displayName, false) } ?: withSelf
        }
        return ContractManagerCandidates(withStored.map { it.copy(current = stored != null && it.actorId == stored) }, page.truncated)
    }

    /**
     * 대상 접수 건의 저장된 담당자 계정 키(MASTER '계약 진행' 섹션 — 모양 검사를 통과한 값). 섹션 없음·읽기 실패·접수 건 없음·이관 건·관리자 아닌 참조(`system:demo-seed`)는 null —
     * 항상 200. 활성 OPERATOR 담당자가 있는 접수 건이면 current로 드러나고(같은 역할의 상세 열람 범위 안의 정보), 없음·비활성·실패는 서로 구별되지 않는다(current 없음). 감사는 남기지 않는다: 돌려주는 것은 이미 목록에 있는 후보의 표시(불리언)뿐이다 [구현 관례].
     */
    @Suppress("UNCHECKED_CAST")
    private fun storedManager(target: ContractManagerTarget): String? {
        val found = gateway.contractIntakes(target.businessId) as? MasterCallResult.Found ?: return null
        val intakes = found.document["intakes"] as? List<Map<String, Any?>> ?: return null
        val manager = intakes.firstOrNull { (it["intakeSeq"] as? Number)?.toLong() == target.intakeSeq.toLong() }?.get("contractManager") as? String ?: return null
        return manager.takeIf { it.startsWith(ADMIN_PREFIX) }?.removePrefix(ADMIN_PREFIX)
    }

    private companion object {
        const val ADMIN_PREFIX = "admin:"
        const val OPERATOR = "OPERATOR"
    }
}
