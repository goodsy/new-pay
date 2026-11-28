package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovedBusinessCriticalChangeCommand
import com.paycore.master.merchant.application.provided.BusinessCriticalChangeUseCase
import com.paycore.master.merchant.application.provided.ProposeBusinessCriticalChangeCommand
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.ApprovalEvidenceStore
import com.paycore.master.merchant.application.required.BusinessChangeProposalStore
import com.paycore.master.merchant.application.required.BusinessContractMerchantHistoryLookup
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import com.paycore.master.merchant.domain.ApprovedBusinessChange
import com.paycore.master.merchant.domain.BusinessChangeDigest
import com.paycore.master.merchant.domain.BusinessChangeProposal
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.nio.charset.StandardCharsets
import java.util.HexFormat

/**
 * 사업자 중요정보 변경 2인 경로 Use Case(BIZ-CRIT-APPROVAL-001 §3.3 — S2). 기존 [AdminCommandExecutor](MASTER 로컬 TX 하나)를 쓴다(P-13 — 새 승인 프레임워크 없음).
 *
 * - [propose]: `admin_request` 예약 → **사업자 행 잠금**(profile 포함) → lockedNow → 계약·가맹점 이력(잠금 뒤) → Domain [com.paycore.master.merchant.domain.Business.proposeCriticalChange]
 *   → beginWrites → 제안 행 INSERT(+ `h1:` digest). 사업자·하위 행·version·변경 이력·Outbox는 쓰지 않는다(m-5 — 이력은 실행 때만). reference = 제안 ID.
 * - [applyWithEvidence]: 근거 1~3(실행기) → 사업자 잠금 → lockedNow(근거 4 — 만료) → 제안 읽기(없으면 `PROPOSAL_CONTENT_MISMATCH`) → 이력(잠금 뒤) → digest **재계산**
 *   → Domain [com.paycore.master.merchant.domain.Business.applyApprovedChange](제안 대조·요청자 일치·생명주기·version·차단 칸만 — 인원 재판정 없음) → beginWrites → diff 저장(version +1)
 *   → 이력·Outbox `BusinessProfileUpdated`(changes 마스킹·사유·approvalId·proposalId·요청자·승인자·revalidatedBy·revalidatedAt = 승인자·승인 시각 — §3.3 대조 확인) → 근거 EXECUTED(실행기).
 * - 잠금 순서: admin_request → business → (하위 행·계약·가맹점·제안 읽기) → 근거 행(§6). 1인 수정·계약 생성·가맹점 등록과 같은 앞 순서라 교착 없음.
 * - digest 키 = 요청 지문과 같은 MASTER fingerprint 키([RequestFingerprintDigester] — §3.2). 값·원문은 로그에 남기지 않는다.
 * - Outbox payload는 1인 수정과 같다({businessId, registrationStatus, version} — 개인정보·상호 없음, 권리 Snapshot 없음 — P-12).
 */
@Service
class BusinessCriticalChangeService(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    ids: MasterIdGenerator,
    clock: MasterClock,
    private val metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
    private val fingerprints: RequestFingerprintDigester,
    evidences: ApprovalEvidenceStore,
    private val businesses: BusinessRepository,
    private val proposals: BusinessChangeProposalStore,
    private val histories: BusinessContractMerchantHistoryLookup,
) : BusinessCriticalChangeUseCase {

    private val log = LoggerFactory.getLogger(BusinessCriticalChangeService::class.java)

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher, fingerprints, evidences)

    override fun propose(command: ProposeBusinessCriticalChangeCommand): AdminCommandResult =
        executor.execute(AdminOperation.PROPOSE_BUSINESS_CRITICAL_CHANGE, command.request, BusinessCommandFingerprintFields.of(command)) { work ->
            val stored = businesses.lockWithProfile(command.businessId) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)
            work.lockedNow()
            val history = histories.of(command.businessId)
            val proposal = stored.business.proposeCriticalChange(
                stored.profile, command.businessType, command.displayName, command.profile, command.reason, command.evidenceRef, command.version, history,
                BusinessChangeProposalId(work.newId(BusinessChangeProposalId.PREFIX)), work.now, command.request.actor,
            )
            work.beginWrites()
            proposals.insert(proposal, digestOf(proposal), work.requestRecordId)
            proposal.id.value
        }

    override fun applyWithEvidence(command: ApprovedBusinessCriticalChangeCommand): AdminCommandResult =
        executor.executeApproved(command.operation, command.request, command.evidence, command.window, ApprovedCommands.fingerprintFields(command)) { work ->
            val stored = businesses.lockWithProfile(command.businessId) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)
            work.lockedNow()
            val history = histories.of(command.businessId)
            val approved = ApprovedBusinessChange(command.businessId, command.baseVersion, command.proposalId, command.contentDigest, command.evidence)
            // S3 재리뷰 N-6: 저장 제안 읽기(Mapper 재구성 — 불변식 위반은 저장 결함)도 무결성 결함 경계 안. 없는 제안은 업무 거절 그대로(MasterRuleViolation은 통과).
            val (edit, proposal) = integrityGuarded(command) {
                val found = proposals.find(command.proposalId) ?: throw MasterRuleViolation(MasterRejection.PROPOSAL_CONTENT_MISMATCH)
                stored.business.applyApprovedChange(stored.profile, found, approved, digestOf(found), history, work.now) to found
            }
            work.beginWrites()
            businesses.update(edit.business, stored.profile, edit.profile)
            val business = edit.business
            val payload = mapOf("businessId" to business.id.value, "registrationStatus" to business.registrationStatus.name, "version" to business.version)
            val e = command.evidence
            val detail = buildMap<String, Any?> {
                putAll(payload)
                put("reason", proposal.reason.value)
                put("evidence", BusinessHistoryEntries.evidenceFlag(true))
                put("changes", edit.change.entries.map { BusinessHistoryEntries.of(it, created = false) })
                put("approvalId", e.approvalId)
                put("proposalId", proposal.id.value)
                put("requestedBy", e.requestedBy.value)
                put("approvedBy", e.approvedBy.value)
                // §3.3: 승인 본문 revalidated=true는 ADMIN TX1이 검사 — 근거 행 승인자·승인 시각이 곧 대조 확인자·시각이다(진위확인 상태 불변 — P-5).
                put("revalidatedBy", e.approvedBy.value)
                put("revalidatedAt", e.approvedAt)
            }
            work.record(AGG_BUSINESS, business.id.value, business.version, UPDATED, payload, historyDetail = detail)
            business.id.value
        }

    /**
     * S2 리뷰 MINOR-2: 근거 실행 판정의 **무결성 결함**(Domain `check` 실패 — 재계산 요약 ≠ 제안 요약·1인 칸·빈 변경, 또는 승인된 내용의 규칙 위반)은 업무 거절이 아니다. 값 없는 WARN(연산·상관 ID·
     * 결함 종류만 — 상호·ID·내용 없음 — 저장 제안 재구성 실패도 포함(N-6))과 지표 `integrity_defect{operation}`을 남기고 그대로 던져 TX를 rollback한다(근거 행 없음). ADMIN은 503을 결과 불명(EXECUTION_UNKNOWN)으로 받고
     * 결정 조회 NONE → 기한 뒤 기한 확인(`APPROVAL_EXPIRED` 저장)으로 닫힌다(명세 §4.1). 저장된 업무 거절([MasterRuleViolation])은 여기서 잡지 않는다.
     */
    private fun <T> integrityGuarded(command: ApprovedBusinessCriticalChangeCommand, block: () -> T): T = try {
        block()
    } catch (e: MasterRuleViolation) {
        throw e
    } catch (e: IllegalStateException) {
        integrityDefect(command, e)
    } catch (e: IllegalArgumentException) {
        // 승인된 내용의 규칙 위반(BusinessProfileRuleViolation)·저장 제안 재구성 실패(N-6) 모두.
        integrityDefect(command, e)
    }

    private fun integrityDefect(command: ApprovedBusinessCriticalChangeCommand, e: RuntimeException): Nothing {
        log.warn("business critical change integrity defect operation={} correlationId={} failure={}", command.operation.code, command.request.correlationId, e.javaClass.simpleName)
        metrics.integrityDefect(command.operation.code)
        throw e
    }

    /** §3.2: `h1:` + HMAC-SHA256(MASTER fingerprint 키, 정규 digest 입력) 소문자 hex — 저장된 제안 내용에서 항상 다시 계산한다. */
    private fun digestOf(proposal: BusinessChangeProposal): BusinessChangeDigest =
        BusinessChangeDigest(AdminCommandExecutor.CURRENT_PREFIX + HexFormat.of().formatHex(fingerprints.digest(proposal.digestInput().toByteArray(StandardCharsets.UTF_8))))

    private companion object {
        const val AGG_BUSINESS = "Business"
        const val UPDATED = "BusinessProfileUpdated"
    }
}
