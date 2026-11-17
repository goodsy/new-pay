package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.BusinessDirectoryPage
import com.paycore.master.merchant.application.provided.BusinessSearchFilter

/** 사업자 목록 읽기 Port(BUSINESS-PROFILE-001 §6.2 — CQRS 조회 측). 구현은 jOOQ `adapter.query`(읽기 전용 TX). 번호는 SQL 마스킹 값만, 원문 번호·대표자 원문·연락처를 SELECT하지 않는다. */
interface BusinessDirectoryReader {
    fun search(filter: BusinessSearchFilter): BusinessDirectoryPage
}
