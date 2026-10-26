package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.LoginName

/**
 * 관리자 계정 Aggregate 저장 Port(§14 — `JpaAdminAccountRepository`). 호출자 TX 안에서만(MANDATORY).
 * - [lock]·[lockAll]: `SELECT … FOR UPDATE`(최신 커밋 값). [lockAll]은 **actor ID 오름차순**으로 잠근다(전역 잠금 순서 §4.13 — 교착 방지).
 * - [update]: 잠근 행의 기대 version과 다르면 OptimisticConflict. 생성 불변 열(actor_id·login_name·created_at)은 바꾸지 않는다(열 권한 1143).
 * - 계정 삭제 연산은 없다(actor ID 재사용 금지 — DELETE 권한 없음).
 */
interface AdminAccountRepository {
    fun find(id: AdminActorId): Versioned<AdminAccount>?

    fun findByLoginName(loginName: LoginName): Versioned<AdminAccount>?

    fun lock(id: AdminActorId): Versioned<AdminAccount>?

    fun lockAll(ids: Collection<AdminActorId>): Map<AdminActorId, Versioned<AdminAccount>>

    fun insert(account: AdminAccount): Versioned<AdminAccount>

    /** 계정이 하나라도 있는가(부트스트랩 조건 보조 — 비잠금, 판정의 권위는 고정 행 PK). */
    fun anyExists(): Boolean

    /** [role]을 활성으로 가진 ACTIVE 계정 수(복구 조건·자동 재활성 조건 — 회차 잠금 아래에서 부른다, §4.9.2). */
    fun countActiveHolders(role: AdminRole): Int

    fun update(current: Versioned<AdminAccount>, next: AdminAccount): Versioned<AdminAccount>
}
