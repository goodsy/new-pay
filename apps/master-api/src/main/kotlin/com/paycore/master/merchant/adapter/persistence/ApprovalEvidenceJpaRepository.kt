package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** admin_approval_evidence Spring Data repository(PK 조회만 — 쓰기는 [JpaApprovalEvidenceStore]가 `persist` + `flush`로 한다). */
interface ApprovalEvidenceJpaRepository : JpaRepository<JpaApprovalEvidenceEntity, String>
