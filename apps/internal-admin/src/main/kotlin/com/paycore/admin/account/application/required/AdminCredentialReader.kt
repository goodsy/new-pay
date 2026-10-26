package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.PasswordHash

/**
 * 비밀번호 단계 (a)의 **비잠금 읽기**(jOOQ — TX 밖, §4.3.2): login_name으로 actor ID·해시·`security_version`. 해시가 없으면(등록 전) null 해시.
 * 잠금 TX 전에 BCrypt 비교를 끝내 같은 계정 요청이 비교 시간 동안 줄 서지 않게 한다.
 */
interface AdminCredentialReader {
    fun findByLoginName(loginName: String): StoredCredential?
}

/** (a) 시점의 저장 자격. toString은 해시를 내지 않는다. */
class StoredCredential(val actorId: AdminActorId, val passwordHash: PasswordHash?, val securityVersion: Long) {
    override fun toString(): String = "StoredCredential(actorId=$actorId, securityVersion=$securityVersion)"
}
