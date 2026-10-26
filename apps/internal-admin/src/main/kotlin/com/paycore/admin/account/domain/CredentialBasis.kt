package com.paycore.admin.account.domain

/**
 * 계정의 현재 자격(비밀번호·TOTP)을 만들 코드의 발급 근거(D-ADMIN1-014·015). 코드 발급 때 정해지고 다음 코드 발급(재설정·재발급) 때만 바뀐다 —
 * 코드 사용·정지·역할 회수와 무관하게 유지된다.
 * - [basis]: 발급 근거 ID(재설정 승인 ID `apr_…`, 1인 생성·재발급 발급 ID `enc_…`, 시스템 경로는 그 근거 — 부트스트랩·복구 회차).
 * - [requester]·[approver]: 그 근거의 요청자·승인자(코드 수령자). 1인 명령은 요청자만(= 코드 수령자), 시스템(부트스트랩·복구)은 둘 다 없음.
 * - [carried]: `ENROLL` 재발급이 자격을 바꾸지 않으므로(D-98 — 재리뷰 m-2, Coordinator 판정 (b)) 등록 완료 전까지 **이전 발급자들**도 배제에 남는다.
 *   등록 완료 때 실제로 쓴 코드의 발급자만 남기고 비운다([settledOnEnrollment]).
 * [issuers] 모두 그 계정의 권한 상승(`ROLE_GRANT`·`ROLE_REACTIVATE`) 요청자·승인자가 될 수 없다([isIssuer]).
 */
class CredentialBasis(val basis: String, val requester: AdminActorId?, val approver: AdminActorId?, carried: Set<AdminActorId> = emptySet()) {
    val carried: Set<AdminActorId> = carried.toSet()

    init {
        require(basis.isNotBlank() && basis.length <= MAX_BASIS) { "credential basis must be 1..$MAX_BASIS characters" }
        require(approver == null || requester != null) { "an approver exists only with a requester" }
        require(approver == null || approver != requester) { "the requester and approver of a basis differ" }
        require(this.carried.size <= MAX_CARRIED) { "at most $MAX_CARRIED carried issuers" }
    }

    val issuers: Set<AdminActorId> get() = setOfNotNull(requester, approver) + carried

    fun isIssuer(actor: AdminActorId): Boolean = actor in issuers

    /** `ENROLL` 재발급(1인): 새 근거·수행자, 이전 발급자들은 등록 완료 전까지 함께 배제(D-98). */
    fun reissuedBy(issueId: String, performer: AdminActorId): CredentialBasis = CredentialBasis(issueId, performer, null, issuers - performer)

    /** 등록 완료: 실제로 쓴 코드(현재 근거)의 발급자만 남긴다. */
    fun settledOnEnrollment(): CredentialBasis = if (carried.isEmpty()) this else CredentialBasis(basis, requester, approver)

    override fun equals(other: Any?): Boolean =
        other is CredentialBasis && other.basis == basis && other.requester == requester && other.approver == approver && other.carried == carried

    override fun hashCode(): Int = basis.hashCode()

    override fun toString(): String = "CredentialBasis(basis=$basis, requester=$requester, approver=$approver, carried=$carried)"

    companion object {
        const val MAX_BASIS = 40

        /** 이월 발급자 상한(저장 열 1000자 안 — actor ID 30자 + 구분자). 넘으면 재발급을 거절한다(재리뷰 m-2 — §23 질문 후보). */
        const val MAX_CARRIED = 32

        /** 1인 명령(계정 생성 — D-ADMIN1-015): 명령 수행자가 코드 수령자. */
        fun oneShotCommand(issueId: String, actor: AdminActorId): CredentialBasis = CredentialBasis(issueId, actor, null)

        /** 2인 승인 재설정(D-ADMIN1-014): 요청자·승인자(코드 수령자). 자격이 바뀌므로 이전 발급자 배제는 풀린다. */
        fun approval(approvalId: String, requester: AdminActorId, approver: AdminActorId): CredentialBasis = CredentialBasis(approvalId, requester, approver)

        /** 시스템 경로(부트스트랩·복구 — 관리자 배제 대상 없음, 호스트 운영자 신뢰 경계 n-H). */
        fun system(basis: String): CredentialBasis = CredentialBasis(basis, null, null)
    }
}
