package com.paycore.admin.account.application.required

/** 계정 목록 조회(jOOQ — CQRS, §5 `AdminAccountListReader`). 조회 서비스만 쓴다(ArchUnit). 해시·암호문·코드 열은 조회 모델에 없다. */
interface AdminAccountListReader {
    /** [includeLoginName]이 거짓이면 login_name 열을 SELECT하지 않는다(D-ADMIN1-017 — SECURITY_ADMIN 조회만 로그인 이름). */
    fun list(limit: Int, includeLoginName: Boolean): List<AdminAccountRow>

    /** FLOW-2d: `ACTIVE` 계정 중 활성 역할에 [role]이 있는 계정(계정 키·표시명만 — login_name 열은 SELECT하지 않는다), 계정 키 내림차순 최대 [limit]행. */
    fun activeRoleHolders(role: String, limit: Int): List<AdminAccountHolderRow>
}

/** 활성 역할 보유자 한 행(계정 키·표시명). */
data class AdminAccountHolderRow(val actorId: String, val displayName: String)

/** 목록 한 행(역할은 MySQL SET 문자열을 enum 이름 목록으로). */
data class AdminAccountRow(
    val actorId: String,
    val loginName: String?,
    val displayName: String,
    val status: String,
    val activeRoles: List<String>,
    val suspendedRoles: List<String>,
    val pendingPurpose: String?,
)
