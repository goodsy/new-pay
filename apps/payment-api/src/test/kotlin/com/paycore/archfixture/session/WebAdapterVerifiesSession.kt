package com.paycore.archfixture.session

import com.paycore.payment.payment.domain.AuthenticationExpiryCause
import com.paycore.payment.payment.domain.AuthenticationFailure
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.VerifiedAuthentication
import java.time.Instant

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): application·domain 밖(예: web adapter)에서 인증 세션 상태를 바꾼다.
 * 대상 = AuthenticationSession.start·verify·fail·expire(각 1건).
 */
class WebAdapterVerifiesSession {
    fun bypass(s: AuthenticationSession, evidence: VerifiedAuthentication, rrd: SecretDigest, ref: AuthenticationRef) {
        s.start("sat_fixture")
        s.verify(evidence, rrd, ref, Instant.EPOCH)
        s.fail(AuthenticationFailure.AUTHENTICATION_FAILED, null)
        s.expire(AuthenticationExpiryCause.TTL_ELAPSED, Instant.EPOCH)
    }
}
