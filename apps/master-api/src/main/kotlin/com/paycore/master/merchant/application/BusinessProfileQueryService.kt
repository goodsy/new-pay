package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.BusinessDetailView
import com.paycore.master.merchant.application.provided.BusinessDirectoryPage
import com.paycore.master.merchant.application.provided.BusinessHistoryPage
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.BusinessSearchFilter
import com.paycore.master.merchant.application.provided.BusinessSummary
import com.paycore.master.merchant.application.provided.ContractIntakeSectionView
import com.paycore.master.merchant.application.required.BusinessDetailReader
import com.paycore.master.merchant.application.required.BusinessDirectoryReader
import com.paycore.master.merchant.application.required.BusinessHistoryReader
import com.paycore.master.merchant.application.required.BusinessLookupReader
import com.paycore.master.merchant.application.required.ContractIntakeReader
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.domain.BusinessContract
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import org.springframework.stereotype.Service

/**
 * [BusinessProfileQueryUseCase](BUSINESS-PROFILE-001 BIZ-2 — Application Query Service): jOOQ 읽기 Port에 위임한다. 쓰기·잠금·Aggregate 로드 없음(CQRS — 명령 측
 * `BusinessRepository`를 쓰지 않는다). 중복 거절 요약([existingSummary])도 같은 상세 읽기 Port로 TX 밖(명령 commit 뒤)에서 읽는다(m-1 — 실패는 호출자가 503).
 */
@Service
class BusinessProfileQueryService(
    private val directory: BusinessDirectoryReader,
    private val lookups: BusinessLookupReader,
    private val details: BusinessDetailReader,
    private val histories: BusinessHistoryReader,
    private val contractIntakes: ContractIntakeReader,
    private val clock: MasterClock,
) : BusinessProfileQueryUseCase {

    override fun search(filter: BusinessSearchFilter): BusinessDirectoryPage = directory.search(filter)

    override fun lookup(number: BusinessRegistrationNumber): List<BusinessSummary> = listOfNotNull(lookups.lookup(number))

    override fun detail(businessId: BusinessId): BusinessDetailView? = details.read(businessId)

    override fun history(businessId: BusinessId, afterSeq: Long): BusinessHistoryPage? {
        require(afterSeq >= 0) { "afterSeq must not be negative" }
        return histories.page(businessId, afterSeq)
    }

    override fun existingSummary(businessId: BusinessId): BusinessSummary? = details.summary(businessId)

    /** FLOW-2b(§4B.11 1): 사업자 계약 칸 = 최신 계약의 상태·적용 중/예정 버전을 조회 시각(MASTER 시계, ms)에 Domain이 계산(표시용 — 판정 아님). */
    override fun contractIntakes(businessId: BusinessId): ContractIntakeSectionView? = contractIntakes.section(businessId)?.let { section ->
        val at = clock.now().truncatedTo(ChronoUnit.MILLIS)
        section.view.copy(judgedAt = at, businessContract = section.contract?.let { contractView(it, section.linkedMerchantCount, at) })
    }

    private fun contractView(c: BusinessContract, linked: Int, at: Instant) = ContractIntakeSectionView.BusinessContractView(
        contractSeq = c.id.contractSeq, migrated = c.migrated, status = c.statusAt(at).name, applyingVersionNo = c.applyingVersionAt(at)?.versionNo,
        scheduledVersionNo = c.scheduledVersionAt(at)?.versionNo, linkedMerchantCount = linked, createdAt = c.createdAt, createdBy = c.createdBy.value,
        versions = c.versions.map { v ->
            ContractIntakeSectionView.Version(
                versionNo = v.versionNo, migrated = v.migrated, originIntakeSeq = v.originIntakeSeq, startsAt = v.startsAt, startDate = v.startDate, endDate = v.endDate,
                services = v.services.map { it.name }.sorted(), documentRef = v.document?.value, closedAt = c.closes.firstOrNull { it.versionNo == v.versionNo }?.closedAt,
                createdAt = v.createdAt, createdBy = v.createdBy.value,
            )
        },
        events = c.events.map { ContractIntakeSectionView.Event(it.eventSeq, it.kind.name, it.evidence?.value, it.actor.value, it.occurredAt) },
    )
}
