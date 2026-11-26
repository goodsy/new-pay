package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.BusinessDetailView
import com.paycore.master.merchant.application.provided.BusinessDirectoryPage
import com.paycore.master.merchant.application.provided.BusinessHistoryPage
import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.application.provided.ContractIntakeSectionView
import com.paycore.master.merchant.domain.BusinessFieldError

/**
 * 사업자 MASTER 내부 응답 모양(BUSINESS-PROFILE-001 §6.2~§6.7 — 골든 `business-profile-response-shapes-v2`). 필드 이름·순서 고정, 값 없는 필드는 `null`.
 * view에 없는 값(원문 사업자등록번호·이력 detail의 다른 키)은 여기서도 만들 수 없다. 시각은 `…T…Z` ms 3자리, 날짜는 `yyyy-MM-dd`.
 */
object BusinessProfileJson {

    fun directory(page: BusinessDirectoryPage): Map<String, Any?> = linkedMapOf(
        "items" to page.items.map {
            linkedMapOf(
                "businessId" to it.businessId, "businessName" to it.businessName, "registrationNumber" to it.registrationNumber,
                "businessType" to it.businessType, "registrationStatus" to it.registrationStatus, "verificationStatus" to it.verificationStatus,
                "representativeNameMasked" to it.representativeNameMasked, "representativeCount" to it.representativeCount, "merchantCount" to it.merchantCount,
                "updatedAt" to AdminChannelResponses.time(it.updatedAt),
            )
        },
        "next" to page.next,
    )

    /** 번호 조회(§6.3): 찾음·못 찾음 같은 모양(`items` 길이만 다르다 — 404 없음). */
    fun lookup(items: List<BusinessSummary>): Map<String, Any?> = linkedMapOf("items" to items.map(::summary))

    /** 상세(§6.4 — 개인정보 원문, 사업자등록번호 원문 — D-BIZ-007). */
    fun detail(v: BusinessDetailView): Map<String, Any?> = linkedMapOf(
        "business" to v.business.let {
            linkedMapOf(
                "businessId" to it.businessId, "businessName" to it.businessName, "registrationNumber" to it.registrationNumber,
                "businessType" to it.businessType, "registrationStatus" to it.registrationStatus, "verificationStatus" to it.verificationStatus,
                "lifecycle" to it.lifecycle, "version" to it.version, "createdAt" to AdminChannelResponses.time(it.createdAt),
                "updatedAt" to AdminChannelResponses.time(it.updatedAt), "merchantCount" to it.merchantCount,
            )
        },
        "registration" to v.registration.let {
            linkedMapOf(
                "corporateRegistrationNumber" to it.corporateRegistrationNumber, "openingDate" to it.openingDate?.toString(), "countryCode" to it.countryCode,
                "englishName" to it.englishName, "phoneNumber" to it.phoneNumber, "email" to it.email,
            )
        },
        "representatives" to v.representatives.map { linkedMapOf("name" to it.name, "representativeType" to it.representativeType) },
        "categories" to v.categories.map { linkedMapOf("categoryType" to it.categoryType, "name" to it.name) },
        "addresses" to v.addresses.map {
            linkedMapOf("addressType" to it.addressType, "postalCode" to it.postalCode, "baseAddress" to it.baseAddress, "detailAddress" to it.detailAddress)
        },
        "contacts" to v.contacts.map { linkedMapOf("name" to it.name, "email" to it.email, "phoneNumber" to it.phoneNumber, "primary" to it.primary) },
    )

    /** MERCHANT-FLOW-001 FLOW-1: '계약 진행' 섹션(접수 건 최신부터, 이력 순번 오름차순). 이관 건의 접수 항목·근거·담당자는 null(화면 '이관' 표시 — R-6). */
    /**
     * 사업자 상세 '계약 진행' 섹션 — golden `contract-intake-section-v2`(MERCHANT-FLOW-001 FLOW-2b, §4B.15 ADMIN 행: `TERMINATED` 제거·`kind`·사업자 계약 칸 추가).
     * 키 순서 고정. 시각은 UTC ms 문자열, 날짜는 `yyyy-MM-dd`. 사유 원문 없음(이력 탭), 근거 참조는 원문(D-FLOW-004 ④).
     */
    fun contractIntakes(v: ContractIntakeSectionView): Map<String, Any?> = linkedMapOf(
        "businessId" to v.businessId,
        "judgedAt" to v.judgedAt?.let(AdminChannelResponses::time),
        "businessContract" to v.businessContract?.let { c ->
            linkedMapOf(
                "contractSeq" to c.contractSeq, "migrated" to c.migrated, "status" to c.status, "applyingVersionNo" to c.applyingVersionNo,
                "scheduledVersionNo" to c.scheduledVersionNo, "linkedMerchantCount" to c.linkedMerchantCount, "createdAt" to AdminChannelResponses.time(c.createdAt),
                "createdBy" to c.createdBy,
                "versions" to c.versions.map { x ->
                    linkedMapOf(
                        "versionNo" to x.versionNo, "migrated" to x.migrated, "originIntakeSeq" to x.originIntakeSeq, "startsAt" to AdminChannelResponses.time(x.startsAt),
                        "startDate" to x.startDate?.toString(), "endDate" to x.endDate?.toString(), "services" to x.services, "documentRef" to x.documentRef,
                        "closedAt" to x.closedAt?.let(AdminChannelResponses::time), "createdAt" to AdminChannelResponses.time(x.createdAt), "createdBy" to x.createdBy,
                    )
                },
                "events" to c.events.map { e ->
                    linkedMapOf(
                        "eventSeq" to e.eventSeq, "kind" to e.kind, "evidenceRef" to e.evidenceRef, "actorRef" to e.actorRef,
                        "occurredAt" to AdminChannelResponses.time(e.occurredAt),
                    )
                },
            )
        },
        "intakes" to v.intakes.map { i ->
            linkedMapOf(
                "intakeSeq" to i.intakeSeq, "status" to i.status, "kind" to i.kind, "targetContractSeq" to i.targetContractSeq, "migrated" to i.migrated,
                "receivedOn" to i.receivedOn?.toString(), "intakeChannel" to i.intakeChannel,
                "contractManager" to i.contractManager, "desiredServices" to i.desiredServices, "version" to i.version,
                "createdAt" to AdminChannelResponses.time(i.createdAt), "updatedAt" to AdminChannelResponses.time(i.updatedAt),
                "transitions" to i.transitions.map { t ->
                    linkedMapOf(
                        "transitionSeq" to t.transitionSeq, "fromStatus" to t.fromStatus, "toStatus" to t.toStatus, "migrated" to t.migrated,
                        "documentRef" to t.documentRef, "actorRef" to t.actorRef, "occurredAt" to AdminChannelResponses.time(t.occurredAt),
                    )
                },
            )
        },
    )

    /** 이력(§6.5 — 저장된 마스킹 값 그대로). */
    fun history(page: BusinessHistoryPage): Map<String, Any?> = linkedMapOf(
        "items" to page.items.map {
            linkedMapOf(
                "seq" to it.seq, "changeType" to it.changeType, "actorRef" to it.actorRef, "occurredAt" to AdminChannelResponses.time(it.occurredAt),
                "reason" to it.reason, "changes" to it.changes,
            )
        },
        "nextAfter" to page.nextAfter,
    )

    /** 명령 성공(§6.7 — 첫 응답·재생 같은 모양, 상태·version 없음 — C-18). */
    fun succeeded(reference: String, replayed: Boolean): Map<String, Any?> =
        linkedMapOf("outcome" to "SUCCEEDED", "reference" to reference, "replayed" to replayed)

    /** 명령 거절(C-12 — reference 키 없음). [existing]은 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`일 때만(D-BIZ-003·C-14). */
    fun rejected(rejection: String, replayed: Boolean, existing: BusinessSummary? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("outcome" to "REJECTED", "rejection" to rejection, "replayed" to replayed).also { body ->
            if (existing != null) body["existing"] = summary(existing)
        }

    /** 값 규칙 위반 400(§4.6 — path·code만, 값 없음). */
    fun validationFailed(errors: List<BusinessFieldError>): Map<String, Any?> = linkedMapOf(
        "error" to AdminChannelResponses.BUSINESS_VALIDATION_FAILED,
        "fieldErrors" to errors.map { linkedMapOf("path" to it.path, "code" to it.code.name) },
    )

    private fun summary(s: BusinessSummary): Map<String, Any?> = linkedMapOf(
        "businessId" to s.businessId, "businessName" to s.businessName, "registrationNumber" to s.registrationNumber,
        "registrationStatus" to s.registrationStatus,
    )
}
