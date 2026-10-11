package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.MerchantId
import java.time.Instant

/**
 * Merchant 구조 조회(CQRS 조회 측 — jOOQ adapter.query가 DTO로 읽는다. 쓰기는 JPA Command 측만). 판정용 권위 조회가 아니라 확인·화면용이다.
 * 권한 범위(어느 운영자가 어느 Merchant를 볼 수 있는가)는 Q-ADMIN-AUTH 이후 web adapter가 강제한다. 등록번호·Secret은 담지 않는다.
 */
interface MerchantStructureQuery {
    fun find(merchantId: MerchantId): MerchantStructureView?
}

data class MerchantStructureView(
    val merchantId: String,
    val businessId: String,
    val lifecycle: String,
    val entitlementRevision: Long,
    val sites: List<SiteView>,
    val accounts: List<AccountView>,
)

data class SiteView(val merchantSiteId: String, val channelType: String, val channelIdentifier: String, val lifecycle: String)

data class AccountView(
    val merchantAccountId: String,
    val lifecycle: String,
    val isDefault: Boolean,
    val issuingContractId: String,
    val issuingAgreementId: String,
    val issuingRevisionNo: Int,
    val assignments: List<AssignmentView>,
    val registeredPaymentMethods: List<String>,
)

data class AssignmentView(
    val assignmentId: String,
    val merchantSiteId: String,
    val role: String,
    val status: String,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
)
