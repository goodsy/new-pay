package com.paycore.admin.account.application.provided

import java.time.Instant

/**
 * 등록 코드 흐름(§4.3.6 — D-ADMIN1-011: 코드만으로 목적의 요소만 설정). 진행 상태 [EnrollmentDraft]는 호출자(S4 웹)가 **서버 세션 속성**에 보관한다.
 * 1. [begin]: 코드 유효(미사용·미폐기·만료 전 — 서버 시계) 확인, 진행 ID 발급. **소비하지 않는다**.
 * 2. [providePassword]·[startTotp]: 목적이 요구하는 요소만 받는다. 새 비밀번호 해시·새 TOTP 시크릿은 암호화해 draft에만(AAD = 문맥 태그·키 버전·용도·진행 ID·대상·목적 — rev 0.4 n-1).
 *    계정 행은 아직 바꾸지 않는다(m-A).
 * 3. [complete]: 로컬 TX — 대상 계정 행 잠금 → 상태 = PENDING_ENROLLMENT이고 대기 사유·근거가 코드와 같은지(H-B) → 코드 원자 소비 → 요소 반영·ACTIVE → 감사.
 * 실패 분류(rev 0.3 n-C): [EnrollmentFailure.CODE_INVALID](미존재·만료·사용·폐기 — 원인 비구분), [EnrollmentFailure.NOT_APPLICABLE](상태·사유 불일치),
 * [EnrollmentFailure.STEP_INVALID](목적에 없는 요소·TOTP 확인 실패·진행 상태 만료). 코드 실패는 계정 실패 계수에 넣지 않는다(지표만).
 */
interface EnrollmentUseCase {
    fun begin(rawCode: String): EnrollmentResult

    fun providePassword(draft: EnrollmentDraft, password: CharArray): EnrollmentResult

    fun startTotp(draft: EnrollmentDraft): EnrollmentResult

    fun complete(draft: EnrollmentDraft, totpCode: String?, context: AdminRequestContext): EnrollmentResult
}

/**
 * 등록 진행 상태(세션 속성 — 원문 비밀 없음). [pendingPasswordHash]·[pendingTotpSecret]은 암호문(nonce ‖ 암호문 ‖ tag)과 키 버전.
 * toString은 암호문을 내지 않는다.
 */
class EnrollmentDraft(
    val progressId: String,
    codeHash: ByteArray,
    val targetActorId: String,
    val purpose: String,
    val basis: String,
    val expiresAt: Instant,
    val pendingPasswordHash: SealedValue? = null,
    val pendingTotpSecret: SealedValue? = null,
) {
    private val hash = codeHash.copyOf()

    val codeHash: ByteArray get() = hash.copyOf()

    fun with(pendingPasswordHash: SealedValue? = this.pendingPasswordHash, pendingTotpSecret: SealedValue? = this.pendingTotpSecret) =
        EnrollmentDraft(progressId, hash, targetActorId, purpose, basis, expiresAt, pendingPasswordHash, pendingTotpSecret)

    override fun toString(): String = "EnrollmentDraft(progressId=$progressId, purpose=$purpose, expiresAt=$expiresAt)"
}

/** 암호문과 키 버전(평문 아님). */
class SealedValue(val keyVersion: Int, bytes: ByteArray) {
    private val value = bytes.copyOf()

    val bytes: ByteArray get() = value.copyOf()

    override fun toString(): String = "SealedValue(keyVersion=$keyVersion, ***)"
}

sealed interface EnrollmentResult {
    data class InProgress(val draft: EnrollmentDraft) : EnrollmentResult

    /** TOTP 등록 시작: [otpauthUri]는 응답 1회만(no-store) — 시크릿 원문이 들어 있다. */
    data class TotpProvisioning(val draft: EnrollmentDraft, val otpauthUri: String) : EnrollmentResult {
        override fun toString(): String = "TotpProvisioning(draft=$draft, otpauthUri=***)"
    }

    data class Completed(val actorId: String) : EnrollmentResult

    data class Failed(val failure: EnrollmentFailure) : EnrollmentResult
}

enum class EnrollmentFailure { CODE_INVALID, NOT_APPLICABLE, STEP_INVALID, PASSWORD_TOO_SHORT, PASSWORD_TOO_LONG }
