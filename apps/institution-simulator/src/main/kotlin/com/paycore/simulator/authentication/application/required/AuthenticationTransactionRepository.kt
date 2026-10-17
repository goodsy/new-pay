package com.paycore.simulator.authentication.application.required

import com.paycore.simulator.authentication.domain.AuthenticationTransaction
import com.paycore.simulator.authentication.domain.ResultRef
import com.paycore.simulator.authentication.domain.TransactionId

/** 가상 인증기관 거래 저장 Port(Aggregate, Simulator 전용 DB). TX 없이 호출하면 거부한다. */
interface AuthenticationTransactionRepository {
    /** INSERT. (client, state)·결과 참조 UNIQUE 충돌은 [DuplicateRegistration]. */
    fun insert(transaction: AuthenticationTransaction)

    fun findByClientAndState(clientId: String, state: String): AuthenticationTransaction?

    fun findByResultRef(resultRef: ResultRef): AuthenticationTransaction?

    fun find(id: TransactionId): AuthenticationTransaction?

    /** 잠가 읽는다(완료·취소 직렬화). */
    fun lock(id: TransactionId): AuthenticationTransaction?

    /** 결과 확정을 반영한다(한 번). */
    fun update(transaction: AuthenticationTransaction)
}

/** 같은 (client, state) 등록이 동시에 들어와 UNIQUE에 걸렸다 — 호출자는 새 TX에서 다시 판정한다. */
class DuplicateRegistration : RuntimeException("duplicate registration", null, false, false)
