package com.paycore.admin.merchantops.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 화면 가맹점 등록(MERCHANT-OPS-002 §4 — B1-1: 사업자·가맹점·Site). 판정 순서(§4.4): 역할(계정 현재 행 ACTIVE + **OPERATOR만** — D-MOPS-006 ①) → 값 형식(C-10 — MASTER Domain과 같은 규칙,
 * 권위는 MASTER) → 등록 버킷(actor별 분당) + 사업자 등록은 등록번호 버킷 1회 원자적 예약(C-13) → **시도 감사 행 `REQUESTED`(ADMIN 로컬 TX 커밋 — 실패하면 MASTER 미호출)** →
 * MASTER 호출(TX 밖) → 등록번호 예약 확정/환불 → 결과 감사 행 → 응답. MASTER 멱등 키는 `mo1.<계정 ULID>.<submissionId>`(계정별 이름공간 — C-4), 상관 ID `mos.<submissionId>`(C-14).
 * 감사·로그 어디에도 MASTER 키 원문·등록번호 평문·채널 식별값·자유 문구가 없다. 중복 등록번호 거절에는 기존 businessId가 없다(D-MOPS-006 ③).
 */
interface MerchantRegistrationUseCase {
    fun register(actorId: String, request: RegistrationRequest, context: AdminRequestContext): MerchantRegistrationResult

    /**
     * 본문이 엄격 해석에 실패했다(§4.4 0 — 본문 원문은 받지 않는다). 사업자 등록이고 [registrationNumberAttempt](첫 최상위 객체의 직속 key `registrationNumber` — Q-MOPS-07 규칙)면
     * 역할 없음 FORBIDDEN / 그 밖 INVALID_INPUT을 `brn=INVALID`로 감사한다. 다른 종류·시도 아님은 감사 없는 InvalidInput. MASTER는 부르지 않는다.
     */
    fun rejectMalformed(actorId: String, kind: RegistrationKind, registrationNumberAttempt: Boolean, context: AdminRequestContext): MerchantRegistrationResult
}

/** 등록 명령 종류(B1-1 사업자·가맹점·Site, B1-2 계약 초안·CSA). [auditAction] = 감사 행위 code(C-5 — 명령별, §6). */
enum class RegistrationKind(val auditAction: String) {
    BUSINESS("MERCHANT_BUSINESS_REGISTER"),
    MERCHANT("MERCHANT_REGISTER"),
    MERCHANT_SITE("MERCHANT_SITE_REGISTER"),
    CONTRACT("MERCHANT_CONTRACT_REGISTER"),
    SERVICE_AGREEMENT("MERCHANT_AGREEMENT_ADD"),

    /** B2-1 계약 Revision 등록(MERCHANT-OPS-002 §9.2 — 1인, C-22: targetRef = 계약 ID, 성공 detail `rev=<n>`). */
    CONTRACT_REVISION("MERCHANT_CONTRACT_REVISION_REGISTER"),
}
