package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantAccountId
import java.time.Instant

/**
 * Demo API Credential 투영 행 INSERT(`merchant_api_credential`, DEMO-SEED-001 §4.5 — D-DEMO-002, D-CPA-001의 Demo 한정 예외). **INSERT만**(갱신·삭제·merge 없음).
 * 기존 읽기 Port [MerchantCredentialStore]와 분리된 쓰기 Port다(기존 인증 경로 무변경 — 인증은 [MerchantCredentialStore.find]로 이 행을 읽는다).
 * 호출자의 PAYMENT 로컬 TX 안에서만 동작한다(MANDATORY). 명령 프로세스에만 빈이 있다(I-23). 사용처는 `DemoCredentialIssueService`뿐(I-22).
 */
interface DemoCredentialProjectionWriter {
    /** INSERT + flush. 같은 keyId가 이미 있으면 [PersistenceFailure.UniqueConflict]([StoreConstraint.MERCHANT_API_CREDENTIAL_KEY_ID]). */
    fun insert(row: DemoCredentialProjection)
}

/** 투영 행 값. 원문 키는 없다 — [secretSha256Hex] = 키 원문 전체 UTF-8의 SHA-256 lowercase hex. environment TEST·status ACTIVE·revision 1은 adapter 상수. */
data class DemoCredentialProjection(
    val keyId: String,
    val merchantAccountId: MerchantAccountId,
    val secretSha256Hex: String,
    val validFrom: Instant,
    val validUntil: Instant,
    val leaseUntil: Instant,
) {
    /** 검증값을 넣지 않는다. */
    override fun toString(): String = "DemoCredentialProjection(keyId=$keyId, validUntil=$validUntil)"
}
