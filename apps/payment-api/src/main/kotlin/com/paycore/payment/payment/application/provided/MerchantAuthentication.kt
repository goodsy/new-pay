package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * 인증된 가맹점 주체(CP-CONTRACT-001 §2 신뢰 컨텍스트). MID·environment는 **Credential에서** 결정되고 요청 본문으로 덮어쓰지 않는다.
 * v1 Credential 권한 범위는 공개 가맹점 API 전체다(D-CPA-001) — operation 축은 아직 없다.
 */
data class MerchantAuthentication(
    val credentialKeyId: CredentialKeyId,
    val merchantAccountId: MerchantAccountId,
    val environment: Environment,
)
