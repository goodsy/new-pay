package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.BusinessContractCommandUseCase
import com.paycore.master.merchant.application.provided.BusinessContractEventCommand
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.BusinessContractRepository
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessContractChange
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

/**
 * 사업자 계약 정지·정지 해제·해지 Use Case(MERCHANT-FLOW-001 §4B.4·§4B.7·§4B.9 — FLOW-2c). [AdminCommandExecutor] **이력 전용 모드**(Outbox·Snapshot 발행기 없음 — §4A.2 M-1).
 *
 * 판정 순서(§4B.7): 사업자 행 잠금(`PESSIMISTIC_WRITE`, version 증가 없음 — 없으면 `BUSINESS_NOT_FOUND`) → `lockedNow` → 생명주기(`BUSINESS_NOT_ACTIVE`) → 대상 = 최신 계약
 * (순번이 다르거나 계약이 없으면 `BUSINESS_CONTRACT_NOT_FOUND` — 과거 계약은 명령 대상이 아니다) → 기대 사건 수(`BUSINESS_CONTRACT_STATE_CONFLICT`) → 전이표(Domain). 사건 시각 =
 * Domain `judgedAt`(max(lockedNow, 마지막 사건 시각)). 잠금 순서: admin_request → business → 사업자 계약(읽기 — 사건은 INSERT만, §4B.9). 같은 계약 동시 명령은 사업자 행 잠금으로
 * 직렬화되어 먼저 잠근 하나만 성공하고 나머지는 기대 사건 수 불일치로 거절된다(DB `uk_bce_terminate`는 이중 방어).
 * 이력 detail: 종류·계약 순번·사건 순번·'사유 및 비고' 원문·근거 참조 원문(원문은 MASTER 이력에만). 로그에 값 없음. 불변식 위반 load는 값 없는 ERROR 경보 후 결함(§4B.12).
 * 효력: 새 가맹점 등록 자격만(BR-BIZ-321) — 가맹점·권리·Snapshot·Outbox를 건드리지 않는다.
 */
@Service
class BusinessContractCommandService(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    ids: MasterIdGenerator,
    clock: MasterClock,
    metrics: MasterOperationalMetrics,
    fingerprints: RequestFingerprintDigester,
    private val businesses: BusinessRepository,
    private val contracts: BusinessContractRepository,
) : BusinessContractCommandUseCase {

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, null, ids, clock, metrics, null, fingerprints)
    private val log = LoggerFactory.getLogger(BusinessContractCommandService::class.java)

    override fun suspend(command: BusinessContractEventCommand): AdminCommandResult =
        run(AdminOperation.SUSPEND_BUSINESS_CONTRACT, SUSPENDED, command) { business, contract, work ->
            contract.suspend(business, command.expectedEventCount, command.reason, work.now, command.request.actor, command.evidence)
        }

    override fun resume(command: BusinessContractEventCommand): AdminCommandResult =
        run(AdminOperation.RESUME_BUSINESS_CONTRACT, RESUMED, command) { business, contract, work ->
            contract.resume(business, command.expectedEventCount, command.reason, work.now, command.request.actor, command.evidence)
        }

    override fun terminate(command: BusinessContractEventCommand): AdminCommandResult =
        run(AdminOperation.TERMINATE_BUSINESS_CONTRACT, TERMINATED, command) { business, contract, work ->
            contract.terminate(business, command.expectedEventCount, command.reason, work.now, command.request.actor, command.evidence)
        }

    private fun run(
        operation: AdminOperation,
        eventType: String,
        command: BusinessContractEventCommand,
        decide: (Business, BusinessContract, AdminCommandExecutor.Work) -> BusinessContractChange,
    ): AdminCommandResult = executor.execute(operation, command.request, fingerprint(command)) { work ->
        val business = businesses.lock(command.businessId) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)
        work.lockedNow()
        business.requireActive()
        val contract = try {
            contracts.latest(command.businessId)
        } catch (e: IllegalArgumentException) {
            log.error("stored rows violate a domain invariant alert=BUSINESS_CONTRACT_LOAD_INVARIANT businessId={} aggregate=contract", command.businessId.value)
            throw e
        }
        if (contract == null || contract.id.contractSeq != command.contractSeq) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_NOT_FOUND)
        val change = decide(business, contract, work)
        work.beginWrites()
        contracts.insert(change)
        val event = checkNotNull(change.event) { "a contract command creates one event" }
        work.recordHistory(
            AGG_CONTRACT, command.businessId.value, eventType,
            buildMap {
                put("businessId", command.businessId.value)
                put("contractSeq", contract.id.contractSeq)
                put("eventSeq", event.eventSeq)
                put("kind", event.kind.name)
                put("occurredAt", event.occurredAt.toString())
                put("reason", event.reason.value)
                event.evidence?.let { put("evidenceRef", it.value) }
            },
        )
        command.businessId.value
    }

    /** 멱등 지문 필드(공통 제외 — 계약 순번·기대 사건 수·사유·근거, null = `~`). 저장 값은 `h1:` HMAC(SEC-BIZ-001). */
    private fun fingerprint(c: BusinessContractEventCommand): List<String?> =
        listOf(c.businessId.value, c.contractSeq.toString(), c.expectedEventCount.toString(), c.reason.value, c.evidence?.value)

    private companion object {
        const val AGG_CONTRACT = "BusinessContract"
        const val SUSPENDED = "BusinessContractSuspended"
        const val RESUMED = "BusinessContractResumed"
        const val TERMINATED = "BusinessContractTerminated"
    }
}
