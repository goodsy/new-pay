package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.provided.NewPaymentIdentifiers
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import java.time.Instant

/** 서버 시계(CP-CONTRACT-001 §2 evaluatedAt·createdAt). 가맹점 시각을 쓰지 않는다. 시험은 고정·이동 가능한 대역을 쓴다. */
interface PaymentClock {
    fun now(): Instant
}

/**
 * 서버 생성 식별자(CP-API-001 명세 §3.4): paymentId `pay_` + ULID 26자, paymentKey `pk_` + 256bit base64url, 멱등 기록 ID·eventId UUID v4.
 * 매 호출이 새 값 묶음을 만든다.
 */
interface PaymentIdentifierGenerator {
    fun newIdentifiers(): NewPaymentIdentifiers
}

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
