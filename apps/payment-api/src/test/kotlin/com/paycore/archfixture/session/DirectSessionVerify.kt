package com.paycore.archfixture.session

import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.VerifiedAuthentication
import java.time.Instant

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): `AuthenticationResultCheck` 판정 순서(R3·R6·R5·R7…)를 건너뛰고 세션을 바로 VERIFIED로 만든다.
 * 대상 = AuthenticationSession.verify.
 */
class DirectSessionVerify {
    fun bypass(s: AuthenticationSession, evidence: VerifiedAuthentication, rrd: SecretDigest, ref: AuthenticationRef) {
        s.verify(evidence, rrd, ref, Instant.EPOCH)
    }
}
