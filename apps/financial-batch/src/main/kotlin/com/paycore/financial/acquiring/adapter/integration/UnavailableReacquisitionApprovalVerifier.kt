package com.paycore.financial.acquiring.adapter.integration

import com.paycore.financial.acquiring.application.required.ReacquisitionApprovalVerifier
import com.paycore.financial.acquiring.domain.ReacquisitionApprovalCommand
import org.springframework.stereotype.Component

/**
 * 재매입 승인 서명 채널 운영 구현 — **채널 없음(fail-closed)**. ADMIN 서명 채널(키·서명 형식·재전송 방지)은 ACQ-ADMIN-001이 만든다(D-ACQ-011 "ADMIN 입력은 후속, 그 전
 * 재매입 실행 불가"). 그 전까지 어떤 서명도 검증하지 않는다 — 승인 기록이 만들어지지 않는다.
 */
@Component
class UnavailableReacquisitionApprovalVerifier : ReacquisitionApprovalVerifier {
    override fun verify(command: ReacquisitionApprovalCommand, signature: String): Boolean = false
}
