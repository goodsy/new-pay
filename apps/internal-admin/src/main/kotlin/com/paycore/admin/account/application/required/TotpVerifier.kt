package com.paycore.admin.account.application.required

import java.time.Instant

/**
 * RFC 6238 TOTP 검증 Port(SHA-1·6자리·30초 — D-04, 자체 구현 adapter). [matchingStep]은 [at]의 단계 ±[windowSteps] 안에서 코드가 맞는 단계를 돌려준다
 * (없으면 null). 마지막 사용 단계 이하 거부(재사용 금지)는 계정 행 잠금 아래 domain이 판정한다(§4.3.4).
 */
interface TotpVerifier {
    fun matchingStep(secret: ByteArray, code: String, at: Instant, windowSteps: Int): Long?

    fun stepAt(at: Instant): Long
}
