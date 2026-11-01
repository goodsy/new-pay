package com.paycore.archfixture.democredential

import com.paycore.payment.payment.application.required.DemoCredentialIssuanceStore
import com.paycore.payment.payment.application.required.DemoCredentialProjectionWriter

/**
 * ArchitectureSpec 위반 fixture(시험 전용 — 운영 classpath에 없음): 발급 서비스를 거치지 않고 Demo Credential 쓰기 Port를 직접 쓰는 옆문.
 * `demoCredentialWritersOnlyFromIssueService`가 두 Port 의존을 모두 잡아야 한다(DEMO-SEED-001 I-22).
 */
@Suppress("unused")
class SideDoorCredentialWriter(private val writer: DemoCredentialProjectionWriter, private val issuances: DemoCredentialIssuanceStore) {
    fun sneak() {
        issuances.find()
        writer.toString()
    }
}
