package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.BusinessContractRepository
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.ContractIntakeRepository
import com.paycore.master.merchant.application.required.ContractIntakeTransitionStore
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessContractChange
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractIntake
import com.paycore.master.merchant.domain.ContractIntakeChange
import com.paycore.master.merchant.domain.ContractIntakeCompletion
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.ContractIntakeTransitionInput
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager

/**
 * 계약 접수 건 명령 Use Case(MERCHANT-FLOW-001 §4B.6·§4B.7·§4B.9 — FLOW-2b). [AdminCommandExecutor]의 **이력 전용 모드**(Outbox·Snapshot 발행기 없음 — §4A.2 M-1, ArchUnit ②).
 *
 * 판정 순서(§4B.7): 요청 형식(parser 400) → 사업자 행 잠금(`PESSIMISTIC_WRITE`, version 증가 없음 — 없으면 `BUSINESS_NOT_FOUND`) → `lockedNow` → 사업자 생명주기 → 대상 존재
 * (`CONTRACT_INTAKE_NOT_FOUND`) → 기대값(version) → 업무 규칙(Domain). 잠금 순서: admin_request → business → 접수 건 → 사업자 계약(읽기 — 새 행은 INSERT만, §4B.9).
 * 판정 뒤 `beginWrites` → 접수 건(insert/update) → 전이 이력 → 사업자 계약 새 행(계약 완료) → 변경 이력(`ContractIntake` aggregate, id = businessId).
 *
 * 이력 detail: 종류·대상·상태·순번·버전·'사유 및 비고' 원문·근거 참조 원문(원문은 MASTER 이력·전이 이력에만 — PII #32·#33). 로그에는 값이 없다.
 * 불변식 위반으로 저장 행을 load하지 못하면(`IllegalArgumentException` — 데이터 손상 신호) 값 없는 ERROR 로그를 남기고 결함으로 올린다(명세 §4B.12 — 운영 알림 대상).
 */
@Service
class ContractIntakeCommandService(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    ids: MasterIdGenerator,
    clock: MasterClock,
    metrics: MasterOperationalMetrics,
    fingerprints: RequestFingerprintDigester,
    private val businesses: BusinessRepository,
    private val intakes: ContractIntakeRepository,
    private val transitions: ContractIntakeTransitionStore,
    private val contracts: BusinessContractRepository,
) : ContractIntakeCommandUseCase {

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, null, ids, clock, metrics, null, fingerprints)
    private val log = LoggerFactory.getLogger(ContractIntakeCommandService::class.java)

    override fun open(command: OpenContractIntakeCommand): AdminCommandResult =
        executor.execute(AdminOperation.OPEN_CONTRACT_INTAKE, command.request, ContractIntakeCommandFingerprintFields.of(command)) { work ->
            val business = lock(command.businessId)
            work.lockedNow()
            val latest = loaded(command.businessId, "intake") { intakes.latest(command.businessId) }
            val latestContract = loaded(command.businessId, "contract") { contracts.latest(command.businessId) }
            val change = ContractIntake.open(business, latest, latestContract, command.expectedKind, command.items, work.now, command.request.actor, command.reason)
            work.beginWrites()
            intakes.insert(change.intake)
            val seq = transitions.append(change.transition)
            history(work, OPENED, change, seq)
            command.businessId.value
        }

    override fun move(command: MoveContractIntakeCommand): AdminCommandResult =
        executor.execute(AdminOperation.MOVE_CONTRACT_INTAKE, command.request, ContractIntakeCommandFingerprintFields.of(command)) { work ->
            val business = lock(command.businessId)
            work.lockedNow()
            val intake = target(business, command.businessId, command.intakeSeq)
            val change = intake.transitionTo(business, command.target, command.expectedVersion, ContractIntakeTransitionInput(reason = command.reason), work.now, command.request.actor)
            work.beginWrites()
            intakes.update(change.intake)
            val seq = transitions.append(change.transition)
            history(work, MOVED, change, seq)
            command.businessId.value
        }

    override fun complete(command: CompleteContractIntakeCommand): AdminCommandResult =
        executor.execute(AdminOperation.COMPLETE_CONTRACT_INTAKE, command.request, ContractIntakeCommandFingerprintFields.of(command)) { work ->
            val business = lock(command.businessId)
            work.lockedNow()
            val intake = target(business, command.businessId, command.intakeSeq)
            val latestContract = loaded(command.businessId, "contract") { contracts.latest(command.businessId) }
            val input = ContractIntakeTransitionInput(
                reason = command.reason, document = command.document, completion = ContractIntakeCompletion(command.terms, latestContract),
            )
            val change = intake.transitionTo(business, ContractIntakeStatus.COMPLETED, command.expectedVersion, input, work.now, command.request.actor)
            work.beginWrites()
            intakes.update(change.intake)
            val seq = transitions.append(change.transition)
            contracts.insert(checkNotNull(change.contract) { "a completion creates business contract rows" })
            history(work, COMPLETED, change, seq)
            command.businessId.value
        }

    private fun lock(id: BusinessId): Business = businesses.lock(id) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)

    /** 대상 존재(생명주기 다음 — §4B.7 판정 순서). */
    private fun target(business: Business, id: BusinessId, seq: Int): ContractIntake {
        business.requireActive()
        return loaded(id, "intake") { intakes.find(id, seq) } ?: throw MasterRuleViolation(MasterRejection.CONTRACT_INTAKE_NOT_FOUND)
    }

    /** 저장 행 → Domain 복원. 불변식 위반(데이터 손상)은 값 없는 ERROR 로그(운영 알림 대상 — §4B.12) 뒤 결함으로 올린다(업무 거절 아님 → 503). */
    private fun <T> loaded(id: BusinessId, what: String, read: () -> T): T = try {
        read()
    } catch (e: IllegalArgumentException) {
        log.error("stored rows violate a domain invariant alert=BUSINESS_CONTRACT_LOAD_INVARIANT businessId={} aggregate={}", id.value, what)
        throw e
    }

    private fun history(work: AdminCommandExecutor.Work, eventType: String, change: ContractIntakeChange, transitionSeq: Int) {
        val intake = change.intake
        val transition = change.transition
        val detail = buildMap<String, Any?> {
            put("businessId", intake.businessId.value)
            put("intakeSeq", intake.seq)
            put("kind", intake.kind.name)
            put("targetContractSeq", intake.targetContractSeq)
            put("fromStatus", transition.from?.name)
            put("toStatus", transition.to.name)
            put("transitionSeq", transitionSeq)
            put("version", intake.version)
            transition.reason?.let { put("reason", it.value) }
            transition.document?.let { put("documentRef", it.value) }
            change.contract?.let { put("businessContract", contractDetail(it)) }
        }
        work.recordHistory(AGG_INTAKE, intake.businessId.value, eventType, detail)
    }

    private fun contractDetail(c: BusinessContractChange): Map<String, Any?> {
        val version = checkNotNull(c.version)
        return linkedMapOf(
            "contractSeq" to c.contract.id.contractSeq,
            "newContract" to c.newContract,
            "versionNo" to version.versionNo,
            "startDate" to version.startDate?.toString(),
            "endDate" to version.endDate?.toString(),
            "services" to version.services.map { it.name }.sorted(),
            "closedVersionNo" to c.close?.versionNo,
        )
    }

    private companion object {
        const val AGG_INTAKE = "ContractIntake"
        const val OPENED = "ContractIntakeOpened"
        const val MOVED = "ContractIntakeMoved"
        const val COMPLETED = "ContractIntakeCompleted"
    }
}
