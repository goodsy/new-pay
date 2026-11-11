package com.paycore.admin.merchantops.application.provided

/**
 * 화면 등록 입력(엄격 해석을 통과한 JSON 문자열 그대로 — 형식 검사는 Use Case). [submissionId] = 브라우저가 폼 제출 단위로 만든 UUID v4 소문자 hex 32자(재전송에 같은 값 — §4.2).
 * 등록번호·채널 식별값은 toString에 나오지 않는다(로그·예외 비노출).
 */
sealed interface RegistrationRequest {
    val submissionId: String
    val kind: RegistrationKind

    class Business(override val submissionId: String, val registrationNumber: String, val displayName: String) : RegistrationRequest {
        override val kind = RegistrationKind.BUSINESS

        override fun toString(): String = "RegistrationRequest.Business(registrationNumber=****)"
    }

    class Merchant(override val submissionId: String, val businessId: String, val displayName: String) : RegistrationRequest {
        override val kind = RegistrationKind.MERCHANT

        override fun toString(): String = "RegistrationRequest.Merchant(businessId=$businessId)"
    }

    class MerchantSite(override val submissionId: String, val merchantId: String, val channelType: String, val channelIdentifier: String) : RegistrationRequest {
        override val kind = RegistrationKind.MERCHANT_SITE

        override fun toString(): String = "RegistrationRequest.MerchantSite(merchantId=$merchantId, channelType=$channelType)"
    }

    /**
     * B1-2 계약 초안(Revision 1). [scopeSiteIds]가 비면 MERCHANT Scope. 날짜는 Asia/Seoul 날짜 `YYYY-MM-DD`(C-7 — Use Case가 그 날 00:00 KST의 UTC 시각으로 바꾼다),
     * [effectiveToDate]는 그 날 0시에 끝(미포함), 없으면 종료 없음. 시작 소급 여부는 판정하지 않는다(MASTER Domain — §3.4).
     */
    class Contract(
        override val submissionId: String,
        val partyBusinessId: String,
        val scopeMerchantId: String,
        val scopeSiteIds: List<String>,
        val effectiveFromDate: String,
        val effectiveToDate: String?,
    ) : RegistrationRequest {
        override val kind = RegistrationKind.CONTRACT

        override fun toString(): String = "RegistrationRequest.Contract(scopeMerchantId=$scopeMerchantId, sites=${scopeSiteIds.size})"
    }

    /**
     * B2-1 계약 Revision 등록(MERCHANT-OPS-002 §9.2 — 1인, 미승인으로 남는다). Scope·날짜 규칙은 [Contract]와 같다(C-7). 시작 소급은 MASTER Domain이 판정한다(D-MOPS-007 ②).
     */
    class ContractRevision(
        override val submissionId: String,
        val contractId: String,
        val scopeMerchantId: String,
        val scopeSiteIds: List<String>,
        val effectiveFromDate: String,
        val effectiveToDate: String?,
    ) : RegistrationRequest {
        override val kind = RegistrationKind.CONTRACT_REVISION

        override fun toString(): String = "RegistrationRequest.ContractRevision(contractId=$contractId, sites=${scopeSiteIds.size})"
    }

    /** B1-2 서비스 계약(CSA) 추가 — 현재 서비스 종류 `CARD_PG_PAYMENT` 하나(D-MD-002). */
    class ServiceAgreement(override val submissionId: String, val contractId: String, val serviceKind: String) : RegistrationRequest {
        override val kind = RegistrationKind.SERVICE_AGREEMENT

        override fun toString(): String = "RegistrationRequest.ServiceAgreement(contractId=$contractId)"
    }

    /** B3-1 결제수단 개통 등록(§9.14 — 1인, UNDER_REVIEW로 남는다). 결제수단은 `CARD`만(기존 enum — 형식은 Use Case). */
    class PaymentMethodActivation(override val submissionId: String, val merchantAccountId: String, val paymentMethod: String) : RegistrationRequest {
        override val kind = RegistrationKind.PAYMENT_METHOD_ACTIVATION

        override fun toString(): String = "RegistrationRequest.PaymentMethodActivation(merchantAccountId=$merchantAccountId, paymentMethod=$paymentMethod)"
    }

    /**
     * B3-1 Site 심사 거절(§9.14 — 1인, **REJECTED 고정·되돌릴 수 없음**(D-MS-005, 재신청은 새 Site), 결정값 필드 없음 C-40). [reasonCode]는 거절 사유 목록(C-37),
     * [evidenceRef]는 선택(형식 §9.3) — **감사·로그·toString에 넣지 않는다**(MASTER 본문에만).
     */
    class SiteReviewRejection(override val submissionId: String, val merchantSiteId: String, val reasonCode: String, val evidenceRef: String?) : RegistrationRequest {
        override val kind = RegistrationKind.SITE_REVIEW_REJECTION

        override fun toString(): String = "RegistrationRequest.SiteReviewRejection(merchantSiteId=$merchantSiteId, reasonCode=$reasonCode)"
    }
}
