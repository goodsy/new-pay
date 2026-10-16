package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import java.time.Instant

/** Credential 투영 상태(D-CPA-001). 폐기는 append가 아니라 상태 갱신으로 전파된다(MASTER-CREDENTIAL-001). */
enum class CredentialStatus { ACTIVE, REVOKED }

/**
 * PAYMENT 로컬 Credential 투영 한 행(D-CPA-001). 원문 키는 없고 [secretSha256Hex](키 원문 전체 UTF-8의 SHA-256 lowercase hex)만 있다.
 * [leaseUntil]: 이 행이 최신이라고 믿을 수 있는 한계 — 전파가 끊기면 폐기를 놓칠 수 있으므로 지나면 거절한다(fail-closed, "lease 상한").
 */
data class StoredMerchantCredential(
    val keyId: CredentialKeyId,
    val merchantAccountId: MerchantAccountId,
    val environment: Environment,
    val secretSha256Hex: String,
    val status: CredentialStatus,
    val validFrom: Instant,
    val validUntil: Instant?,
    val leaseUntil: Instant,
)

/**
 * Credential 투영 읽기(PAYMENT DB, 캐시 없음). 쓰기(투영 적용)는 MASTER-CREDENTIAL-001의 수신 경로가 추가한다 — 지금 main 코드에는 쓰기
 * 경로가 없고 runtime 권한도 SELECT뿐이다. 호출자 TX 안에서만 동작한다.
 */
interface MerchantCredentialStore {
    fun find(keyId: CredentialKeyId): StoredMerchantCredential?
}
