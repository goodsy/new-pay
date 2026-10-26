package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AdminActorId

/** 서버 생성 식별자: actor ID(`adm_`+ULID), 등록 코드 발급 ID(`enc_`+ULID), 등록 진행 ID(128비트 난수 — 세션 안 진행 상태 식별, §4.3.6). */
interface AdminIdGenerator {
    fun newActorId(): AdminActorId

    fun newEnrollIssueId(): String

    fun newProgressId(): String
}
