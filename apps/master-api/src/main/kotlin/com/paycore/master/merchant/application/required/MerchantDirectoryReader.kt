package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantDirectoryPage
import com.paycore.master.merchant.application.provided.MerchantSearchFilter

/** 가맹점 목록 읽기 Port(CQRS 조회 측, MERCHANT-OPS-001 §3.1). 구현은 jOOQ `adapter.query`(읽기 전용 TX, 쓰기 금지). 등록번호는 마스킹 값만 돌려준다. */
interface MerchantDirectoryReader {
    fun search(filter: MerchantSearchFilter): MerchantDirectoryPage
}
