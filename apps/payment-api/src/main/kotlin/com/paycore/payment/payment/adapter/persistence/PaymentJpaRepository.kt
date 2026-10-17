package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/*
 * Spring Data repository 공통(이 패키지의 *JpaRepository 전체, adapter.persistence 전용 — ArchUnit springDataRepositoriesOnlyInPersistenceAdapter). application은 이것을 모르고
 * application.required Port 구현(Jpa*Repository·Jpa*Store)만 안다.
 *
 * 잠금 읽기(@Lock PESSIMISTIC_WRITE → MySQL `SELECT … FOR UPDATE`): 판정에 쓰는 행은 잠가서 최신 커밋 값을 읽는다. READ COMMITTED에서도
 * 잠금 읽기는 다른 TX의 커밋을 기다린 뒤 최신 값을 본다. 최초 INSERT 경쟁 처리는 native `INSERT … ON DUPLICATE KEY UPDATE`(no-op)다:
 * 이미 행이 있으면(다른 TX가 아직 커밋 전이어도) 그 행의 잠금을 기다리고, 없으면 새 행을 넣는다. 목록 SELECT 후 INSERT가 아니다.
 */

/** Payment 행 잠금은 [JpaPaymentRepository]가 `lockRow`(find + refresh PESSIMISTIC_WRITE)로 한다(AUTHN-001 검토 #2 m-1). */
interface PaymentJpaRepository : JpaRepository<JpaPaymentEntity, String>
