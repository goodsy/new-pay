package com.paycore.master.merchant.application.required

/**
 * 관리 명령 요청 지문의 비밀 키 HMAC(SEC-BIZ-001 M-2 — D-MOPS-010 ②, D-GOV-001 ②, MERCHANT-OPS-002 §9.29·C-46 rev). Application([com.paycore.master.merchant.application.AdminCommandExecutor])이
 * 연산 코드·명령 필드의 canonical 바이트 **전체**를 넘기고 32바이트 MAC을 받는다 — 지문 형식(`h1:` + hex)과 재생/충돌 판정은 Application이 정한다. 키는 adapter만 안다.
 * 구현은 입력·키·결과를 로그·예외·`toString`에 남기지 않는다.
 */
interface RequestFingerprintDigester {
    /** HMAC-SHA256(MASTER fingerprint 키, [canonical]) — 32바이트. */
    fun digest(canonical: ByteArray): ByteArray

    /**
     * HMAC 전 형식(`m1:` 무염 SHA-256)으로 저장된 `admin_request` 행을 같은 요청의 legacy 계산으로 비교해도 되는가. **Demo 배포에서만 true**(D-MOPS-010 ② "기존 행 호환 비교는 Demo 한정",
     * MERCHANT-OPS-002 C-47 rev·L-25 제거 조건). LIVE는 false — legacy 행에 같은 내용이 와도 409(fail-closed).
     */
    fun legacyComparisonAllowed(): Boolean
}
