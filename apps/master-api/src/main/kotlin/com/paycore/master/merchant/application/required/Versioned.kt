package com.paycore.master.merchant.application.required

/*
 * MASTER 저장 Port 공통 관례(이 패키지의 *Repository·*Store Port 전체, application.required). 모든 메서드는 application이 연 MASTER 로컬 TX 안에서만 동작한다(구현은 TX 없으면 거부).
 * 쓰기 메서드는 Port 안에서 flush해 제약 위반을 [PersistenceFailure]로 드러낸다(commit 시점이 아니라).
 * lock* = `SELECT … FOR UPDATE`(잠금 순서: admin_request → business → merchant → contract → merchant_account, 명세 §3.3).
 */

/** 저장된 값과 그 행의 JPA version(동시 수정 감지 기술 값). 갱신 Port는 기대 version을 받아 오래된 domain으로 덮어쓰지 않는다. */
data class Versioned<T>(val value: T, val version: Long)
