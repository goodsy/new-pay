package com.paycore.payment.payment.application.required

/**
 * 영속 상태와 그것을 읽었을 때의 동시 수정 감지 version(JPA `@Version`). [version]은 업무 값이 아니다(업무 generation은 domain).
 * 갱신 Port는 이 version을 기대값으로 받아, 그 사이 다른 TX가 바꿨으면 [PersistenceFailure.OptimisticConflict]로 거부한다
 * (오래된 domain 객체로 최신 행을 덮어쓰지 않는다).
 */
data class Versioned<out T>(val value: T, val version: Long)
