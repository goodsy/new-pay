package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantAccountId
import java.time.Instant

/**
 * Demo Credential 1회성·감사 행(`demo_credential_issuance`, DEMO-SEED-001 §4.5 — D-DEMO-002·004). 행은 많아야 하나(고정 id 1)이고 **INSERT만** 한다
 * (갱신·삭제 없음 — runtime 권한도 SELECT·INSERT). 호출자의 PAYMENT 로컬 TX 안에서만 동작한다(MANDATORY).
 * 명령 프로세스(`paycore.payment.command=demo-credential`)에만 빈이 있다(I-23). 사용처는 `DemoCredentialIssueService`뿐(I-22, ArchUnit).
 */
interface DemoCredentialIssuanceStore {
    /** 이미 발급했는가(재실행 → ALREADY_ISSUED). 비잠금 읽기. */
    fun find(): DemoCredentialIssuance?

    /**
     * 고정 행을 INSERT하고 flush한다(merge 아님 — 이미 있으면 1062). 경합 패자는
     * [PersistenceFailure.UniqueConflict]([StoreConstraint.DEMO_CREDENTIAL_ISSUANCE]) — TX는 rollback 전용이 된다.
     */
    fun insertOnce(issuance: DemoCredentialIssuance)
}

/** 발급 기록(원문·SHA-256 없음 — keyId만). environment는 항상 TEST(DB CHECK). */
data class DemoCredentialIssuance(
    val keyId: String,
    val merchantAccountId: MerchantAccountId,
    val issuedAt: Instant,
    val actor: String,
    val correlationId: String,
)
