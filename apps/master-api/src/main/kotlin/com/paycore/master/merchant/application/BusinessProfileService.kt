package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CompleteBusinessRegistrationCommand
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessChangeEntry
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfileEdit
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

/**
 * 사업자 기본정보 명령 Use Case(BUSINESS-PROFILE-001 §6.7 — BIZ-1, M-3). 기존 [AdminCommandExecutor](MASTER 로컬 TX 하나 — `admin_request` 멱등·업무 변경·
 * `master_change_history`·Outbox 같은 TX, 응답은 commit 뒤)와 실행기 공통 요청 지문(C-9 — 필드 [BusinessCommandFingerprintFields], 형식은 SEC-BIZ-001부터 `h1:` HMAC —
 * [AdminCommandExecutor] 참조, 키 [RequestFingerprintDigester])을 쓴다.
 *
 * - create: lockedNow → 번호 사전 조회(있으면 저장된 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`, reference = 기존 ID — D-BIZ-003 요약의 근거) → Domain 생성(규칙 재검사)
 *   → beginWrites → 저장(business + 등록정보 + 하위 행, flush) → 이력·Outbox `BusinessCreated`. 동시 생성 경합 진 쪽은 1062(업무 유일키) → 실행기 새 TX 재판정 → 중복 거절로 수렴.
 * - update / complete: `lockWithProfile`(없으면 `BUSINESS_NOT_FOUND`) → lockedNow → Domain 전이(생명주기 → 상태 → version → 변경 계산·사유 → 규칙 재검사)
 *   → beginWrites → diff 저장(종료 → flush → 추가, version 정확히 +1) → 이력·Outbox.
 * - 잠금 순서: admin_request → business → (하위 행 — 같은 business 범위). 가맹점 등록(business → merchant)과 같은 앞 순서라 교착 없음.
 * - 이력 detail은 마스킹 값만(§9 — 사유는 원문, C-13). Outbox payload = {businessId, registrationStatus, version} + 실행기가 붙이는 occurredAt(N-1) — 개인정보·번호·상호 없음,
 *   권리 Snapshot 발행 없음(사업자 등록 상태는 권리 입력이 아니다 — TC-19).
 */
@Service
class BusinessProfileService(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    ids: MasterIdGenerator,
    private val businesses: BusinessRepository,
    clock: MasterClock,
    metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
    fingerprints: RequestFingerprintDigester,
) : BusinessProfileCommandUseCase {

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher, fingerprints)

    override fun create(command: CreateBusinessCommand): AdminCommandResult =
        executor.execute(AdminOperation.CREATE_BUSINESS, command.request, BusinessCommandFingerprintFields.of(command)) { work ->
            work.lockedNow() // 잡을 직렬화 잠금이 없다(동시 생성은 번호 UNIQUE 충돌 → 새 TX 재판정)
            businesses.findIdByRegistrationNumber(command.registrationNumber)?.let {
                throw MasterRuleViolation(MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE, it.value)
            }
            val id = BusinessId(work.newId(BusinessId.PREFIX))
            val edit = when (command.registrationStatus) {
                BusinessRegistrationStatus.DRAFT -> Business.createDraft(
                    id, command.businessType, command.registrationNumber, command.displayName, command.profile, work.now, command.request.actor,
                )
                BusinessRegistrationStatus.REGISTERED -> Business.createRegistered(
                    id, command.businessType, command.registrationNumber, command.displayName, command.profile, work.now, command.request.actor,
                )
                BusinessRegistrationStatus.INACTIVE -> error("a business is never created INACTIVE")
            }
            work.beginWrites()
            businesses.insert(edit.business, edit.profile)
            record(work, CREATED, edit, reason = null, created = true)
            id.value
        }

    override fun update(command: UpdateBusinessCommand): AdminCommandResult =
        executor.execute(AdminOperation.UPDATE_BUSINESS, command.request, BusinessCommandFingerprintFields.of(command)) { work ->
            val stored = businesses.lockWithProfile(command.businessId) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)
            work.lockedNow()
            val edit = stored.business.updateProfile(
                stored.profile, command.businessType, command.displayName, command.profile, command.reason, command.version, work.now, command.request.actor,
            )
            work.beginWrites()
            businesses.update(edit.business, stored.profile, edit.profile)
            record(work, UPDATED, edit, command.reason, created = false)
            command.businessId.value
        }

    override fun completeRegistration(command: CompleteBusinessRegistrationCommand): AdminCommandResult =
        executor.execute(AdminOperation.COMPLETE_BUSINESS_REGISTRATION, command.request, BusinessCommandFingerprintFields.of(command)) { work ->
            val stored = businesses.lockWithProfile(command.businessId) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)
            work.lockedNow()
            val edit = stored.business.completeRegistration(
                stored.profile, command.businessType, command.displayName, command.profile, command.reason, command.version, work.now, command.request.actor,
            )
            work.beginWrites()
            businesses.update(edit.business, stored.profile, edit.profile)
            record(work, COMPLETED, edit, command.reason, created = false)
            command.businessId.value
        }

    private fun record(work: AdminCommandExecutor.Work, eventType: String, edit: BusinessProfileEdit, reason: BusinessChangeReason?, created: Boolean) {
        val business = edit.business
        val payload = mapOf(
            "businessId" to business.id.value,
            "registrationStatus" to business.registrationStatus.name,
            "version" to business.version,
        )
        val detail = buildMap<String, Any?> {
            putAll(payload)
            // 사업자등록번호는 마스킹 값만(D-MD-008 — 옛 RegisterBusiness 이력과 같은 키).
            if (created) put("registrationNumberMasked", business.registrationNumber.masked())
            if (reason != null) put("reason", reason.value)
            put("changes", edit.change.entries.map { historyEntry(it, created) })
        }
        work.record(AGG_BUSINESS, business.id.value, business.version, eventType, payload, historyDetail = detail)
    }

    /** 이력 항목(값은 Domain이 마스킹한 값 — §9). 생성은 이전 값 키를 두지 않는다(before 없음). */
    private fun historyEntry(entry: BusinessChangeEntry, created: Boolean): Map<String, Any?> = when (entry) {
        is BusinessChangeEntry.Value -> if (created) {
            mapOf("field" to entry.field, "after" to entry.after)
        } else {
            mapOf("field" to entry.field, "before" to entry.before, "after" to entry.after)
        }
        is BusinessChangeEntry.Rows -> if (created) {
            mapOf("field" to entry.field, "added" to entry.added)
        } else {
            mapOf("field" to entry.field, "ended" to entry.ended, "added" to entry.added)
        }
    }

    private companion object {
        /** 옛 RegisterBusiness 이력과 같은 aggregate_type(옛 등록 경로는 C-27 전환에서 제거 — 과거 이력 행은 그대로). */
        const val AGG_BUSINESS = "Business"
        const val CREATED = "BusinessCreated"
        const val UPDATED = "BusinessProfileUpdated"
        const val COMPLETED = "BusinessRegistrationCompleted"
    }
}
