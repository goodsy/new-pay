package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.PasswordHash

/**
 * 비밀번호 해시 Port(BCrypt `{bcrypt}` — D-03, cost는 설정 Demo 12). 원문은 `CharArray`로만 받고 호출자가 사용 뒤 지운다.
 * [matches]는 해시가 없을 때(없는 계정·자격 없는 계정)도 **같은 cost의 더미 해시로 비교를 정확히 1회** 하고 false를 돌려준다(경로 동등성 — m-4).
 */
interface PasswordHasher {
    fun hash(raw: CharArray): PasswordHash

    fun matches(raw: CharArray, hash: PasswordHash?): Boolean
}
