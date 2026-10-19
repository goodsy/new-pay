package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * 권리 투영 head 공유 잠금(AUTH-001 rev 0.3 m-9 — AU-04 대체): confirm TX1·TXd가 멱등 행 다음, control보다 **먼저** 인증 MID의 소유 Merchant head를 `FOR SHARE`로 잡는다
 * (잠금 순서 §4.7 — Inbox 적용(head X)과의 교착 방지 F-7). 소유 Merchant가 없거나 head가 없으면 아무것도 잡지 않는다(판정은 뒤의 허용 판정이 같은 at으로 한다).
 * 호출자 TX 안에서만(MANDATORY). 저장소 일시 실패는 [PersistenceFailure].
 */
fun interface EntitlementHeadLockPort {
    fun lockForShare(mid: MerchantAccountId)
}
