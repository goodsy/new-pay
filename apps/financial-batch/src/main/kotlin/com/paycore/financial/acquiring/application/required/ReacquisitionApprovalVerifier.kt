package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.ReacquisitionApprovalCommand

/**
 * ADMIN 서명 채널 검증 Port(2-G — D-ACQ-011·D-ADMIN-007). 재매입 승인 명령은 ADMIN 서명 채널로만 들어온다. **채널은 ACQ-ADMIN-001에서 생긴다** — 지금 운영 구현
 * (`UnavailableReacquisitionApprovalVerifier`)은 언제나 false(fail-closed)라 승인 기록이 만들어지지 않는다.
 */
interface ReacquisitionApprovalVerifier {
    /** [signature]가 [command]에 대한 ADMIN 서명 채널의 유효한 서명인가. */
    fun verify(command: ReacquisitionApprovalCommand, signature: String): Boolean
}
