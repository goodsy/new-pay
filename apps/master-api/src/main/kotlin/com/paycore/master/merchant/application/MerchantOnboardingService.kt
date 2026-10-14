package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.IssueFirstDefaultMerchantAccountCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.BusinessStore
import com.paycore.master.merchant.application.required.ContractStore
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.MerchantAccountStore
import com.paycore.master.merchant.application.required.MerchantSiteStore
import com.paycore.master.merchant.application.required.MerchantStore
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.PaymentMethodActivationStore
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractScope
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantAccountIssuance
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethodActivation
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteAssignmentId
import com.paycore.master.merchant.domain.SiteChannel
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * MASTER 관리 명령(MASTER-DOMAIN-001). 각 명령은 [AdminCommandExecutor]의 MASTER 로컬 TX 하나다. 잠금 순서(교착 방지, 명세 §3.3):
 * admin_request → business → merchant → contract → merchant_account. Merchant 행은 Merchant 안 관계 변경의 직렬화 지점이다(최초 Default
 * 발급의 "MID 0개 확인 → 발급"을 다른 키의 동시 요청과 직렬화한다 — 서로 다른 새 행 사이의 경쟁은 version으로 막을 수 없다).
 *
 * 업무 본문은 항상 "잠금·읽기 → domain 판정 → beginWrites → 저장·이력·Outbox" 순서다. 거절(판정 단계 [MasterRuleViolation])은 쓰기 없이
 * 멱등 결과로만 저장된다.
 */
@Service
class MerchantOnboardingService(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    private val ids: MasterIdGenerator,
    private val businesses: BusinessStore,
    private val merchants: MerchantStore,
    private val sites: MerchantSiteStore,
    private val contracts: ContractStore,
    private val accounts: MerchantAccountStore,
    private val activations: PaymentMethodActivationStore,
    clock: MasterClock,
    metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
) : MerchantOnboardingUseCase {

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher)

    override fun registerBusiness(command: RegisterBusinessCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_BUSINESS, command.request, listOf(command.registrationNumber.value, command.displayName.value),
        ) { work ->
            work.lockedNow() // 잡을 직렬화 잠금이 없다(동시 등록은 UNIQUE 충돌 → 재판정)
            // D-MD-008: 국내 사업자등록번호로 Business 하나. 동시 등록 경합은 UNIQUE 충돌 → 새 TX에서 이 판정이 기존 행을 보고 거절로 수렴.
            businesses.findIdByRegistrationNumber(command.registrationNumber)?.let {
                throw MasterRuleViolation(MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE, it.value)
            }
            val business = Business.register(
                BusinessId(work.newId(BusinessId.PREFIX)), command.registrationNumber, command.displayName, work.now, command.request.actor,
            )
            work.beginWrites()
            businesses.insert(business)
            work.record(
                AGG_BUSINESS, business.id.value, 0, "BusinessRegistered",
                payload = mapOf("businessId" to business.id.value, "lifecycle" to business.lifecycle.name),
                // 감사 이력에는 마스킹 값만(D-MD-008). 이벤트에는 넣지 않는다.
                historyDetail = mapOf(
                    "businessId" to business.id.value,
                    "registrationNumberMasked" to business.registrationNumber.masked(),
                    "displayName" to business.displayName.value,
                    "lifecycle" to business.lifecycle.name,
                ),
            )
            business.id.value
        }

    override fun registerMerchant(command: RegisterMerchantCommand): AdminCommandResult =
        executor.execute(AdminOperation.REGISTER_MERCHANT, command.request, listOf(command.businessId.value, command.displayName.value)) { work ->
            val business = businesses.lock(command.businessId) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)
            work.lockedNow()
            val merchant = Merchant.register(
                MerchantId(work.newId(MerchantId.PREFIX)), business, command.displayName, work.now, command.request.actor,
            )
            work.beginWrites()
            merchants.insert(merchant)
            work.record(
                AGG_MERCHANT, merchant.id.value, 0, "MerchantRegistered",
                mapOf(
                    "merchantId" to merchant.id.value, "businessId" to business.id.value, "displayName" to merchant.displayName.value,
                    "lifecycle" to merchant.lifecycle.name, "entitlementRevision" to merchant.entitlementRevision,
                ),
            )
            merchant.id.value
        }

    override fun registerMerchantSite(command: RegisterMerchantSiteCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_MERCHANT_SITE, command.request,
            listOf(command.merchantId.value, command.channelType.name, command.channelIdentifier),
        ) { work ->
            val channel = SiteChannel(command.channelType, command.channelIdentifier)
            val locked = merchants.lock(command.merchantId) ?: throw MasterRuleViolation(MasterRejection.MERCHANT_NOT_FOUND)
            val merchant = locked.value
            work.lockedNow()
            // D-MD-008: 활성 채널 식별값은 같은 Merchant 안에서도, 다른 Merchant와도 중복 불가(예외 승인 기록은 후속).
            sites.findActiveByChannel(channel)?.let { throw MasterRuleViolation(MasterRejection.SITE_CHANNEL_DUPLICATE, it.value) }
            val site = MerchantSite.register(MerchantSiteId(work.newId(MerchantSiteId.PREFIX)), merchant, channel, work.now, command.request.actor)
            // review M-2: Site는 Snapshot `sites[]`·contractWindows에 실린다 — 권리 입력 변경이므로 같은 TX에서 revision +1·발행한다
            // (아니면 다음 lease 갱신이 같은 revision·다른 내용이 되어 PAYMENT가 INTEGRITY_CONFLICT로 Merchant 전체를 막는다).
            merchant.recordEntitlementChange()
            work.beginWrites()
            sites.insert(site)
            merchants.update(merchant, locked.version)
            work.record(
                AGG_SITE, site.id.value, 0, "MerchantSiteRegistered",
                mapOf(
                    "merchantSiteId" to site.id.value, "merchantId" to merchant.id.value, "channelType" to channel.type.name,
                    "channelIdentifier" to channel.identifier, "lifecycle" to site.lifecycle.name,
                    "entitlementRevision" to merchant.entitlementRevision,
                ),
            )
            work.publishEntitlement(merchant)
            site.id.value
        }

    override fun registerContract(command: RegisterContractCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_CONTRACT, command.request,
            listOf(command.partyBusinessId.value) + scopeFields(command.scope) + periodFields(command.effectivePeriod),
        ) { work ->
            val period = EffectivePeriod.ofMillis(command.effectivePeriod.from, command.effectivePeriod.to)
            val business = businesses.lock(command.partyBusinessId) ?: throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_FOUND)
            val scope = validScope(command.scope, business.id)
            work.lockedNow()
            val contract = Contract.register(
                ContractId(work.newId(ContractId.PREFIX)), business, scope, period, work.now, command.request.actor,
            )
            work.beginWrites()
            val version = contracts.insertNew(contract)
            work.record(AGG_CONTRACT, contract.id.value, version, "ContractRegistered", contractPayload(contract, 1))
            contract.id.value
        }

    override fun registerContractRevision(command: RegisterContractRevisionCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_CONTRACT_REVISION, command.request,
            listOf(command.contractId.value) + scopeFields(command.scope) + periodFields(command.effectivePeriod),
        ) { work ->
            val period = EffectivePeriod.ofMillis(command.effectivePeriod.from, command.effectivePeriod.to)
            val partyId = contracts.find(command.contractId)?.businessId ?: throw MasterRuleViolation(MasterRejection.CONTRACT_NOT_FOUND)
            val business = businesses.lock(partyId) ?: error("contract party must exist")
            business.requireActive()
            val scope = validScope(command.scope, business.id)
            val locked = contracts.lock(command.contractId) ?: error("contract must exist")
            work.lockedNow()
            val contract = locked.value
            // 대상 Merchant 변경은 거절(D-MD-013) — Contract.registerRevision이 판정한다.
            val revision = contract.registerRevision(scope, period, work.now, command.request.actor)
            work.beginWrites()
            val version = contracts.update(contract, locked.version)
            work.record(AGG_CONTRACT, contract.id.value, version, "ContractRevisionRegistered", contractPayload(contract, revision.revisionNo))
            "${contract.id.value}#${revision.revisionNo}"
        }

    override fun approveContractRevision(command: ApproveContractRevisionCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.APPROVE_CONTRACT_REVISION, command.request, listOf(command.contractId.value, command.revisionNo.toString()),
        ) { work ->
            // Revision 내용은 불변이라 잠그지 않고 Scope Merchant를 먼저 읽어 잠금 순서(merchant → contract)를 지킨다.
            val unlocked = contracts.find(command.contractId) ?: throw MasterRuleViolation(MasterRejection.CONTRACT_NOT_FOUND)
            val scopeMerchant = unlocked.revision(command.revisionNo)?.scope?.merchantId
                ?: throw MasterRuleViolation(MasterRejection.REVISION_NOT_FOUND)
            val merchant = merchants.lock(scopeMerchant) ?: error("scope merchant must exist")
            val locked = contracts.lock(command.contractId) ?: error("contract must exist")
            work.lockedNow() // 끝난 Revision 승인 거절(D-MD-014)은 잠금 뒤 시각으로 판정한다
            val contract = locked.value
            // 승인자 ≠ 요청자 같은 2인 통제는 Q-ADMIN-AUTH 이후(지금은 행위자 기록만).
            contract.approve(command.revisionNo, work.now, command.request.actor)
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            val version = contracts.update(contract, locked.version)
            merchants.update(merchant.value, merchant.version)
            work.record(
                AGG_CONTRACT, contract.id.value, version, "ContractRevisionApproved",
                contractPayload(contract, command.revisionNo) + ("entitlementRevision" to merchant.value.entitlementRevision),
            )
            work.publishEntitlement(merchant.value)
            "${contract.id.value}#${command.revisionNo}"
        }

    override fun addServiceAgreement(command: AddServiceAgreementCommand): AdminCommandResult =
        executor.execute(AdminOperation.ADD_SERVICE_AGREEMENT, command.request, listOf(command.contractId.value, command.serviceKind.name)) { work ->
            val locked = contracts.lock(command.contractId) ?: throw MasterRuleViolation(MasterRejection.CONTRACT_NOT_FOUND)
            work.lockedNow()
            val contract = locked.value
            val agreement = contract.addAgreement(
                ContractServiceAgreementId(work.newId(ContractServiceAgreementId.PREFIX)), command.serviceKind, work.now, command.request.actor,
            )
            work.beginWrites()
            val version = contracts.update(contract, locked.version)
            work.record(
                AGG_AGREEMENT, agreement.id.value, 0, "ServiceAgreementAdded",
                mapOf(
                    "agreementId" to agreement.id.value, "contractId" to contract.id.value, "serviceKind" to agreement.serviceKind.name,
                    "contractVersion" to version,
                ),
            )
            agreement.id.value
        }

    override fun issueFirstDefaultMerchantAccount(command: IssueFirstDefaultMerchantAccountCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, command.request,
            listOf(
                command.merchantId.value, command.contractId.value, command.agreementId.value, command.revisionNo.toString(),
                command.primarySiteId.value,
            ),
        ) { work ->
            val businessId = merchantBusiness(command.merchantId)
            val business = businesses.lock(businessId) ?: error("merchant business must exist")
            val merchant = merchants.lock(command.merchantId) ?: error("merchant must exist")
            // merchant 잠금 아래에서 센다: 다른 키의 동시 최초 발급은 여기서 직렬화되고 하나만 성공한다(초기).
            val existingDefault = accounts.findDefault(command.merchantId)
            val existingCount = accounts.countByMerchant(command.merchantId)
            val contract = contracts.lock(command.contractId)?.value ?: throw MasterRuleViolation(MasterRejection.CONTRACT_NOT_FOUND)
            // 마지막 잠금 뒤 시각: 적용 Revision(D-MD-011·015)과 PRIMARY effective_from(소급 금지, D-MD-005)이 잠금 대기 뒤의 시각을 쓴다.
            work.lockedNow()
            val site = sites.find(command.primarySiteId) ?: throw MasterRuleViolation(MasterRejection.SITE_NOT_FOUND)
            val issuance = MerchantAccountIssuance.issueFirstDefault(
                MerchantAccountId(work.newId(MerchantAccountId.PREFIX)), SiteAssignmentId(work.newId(SiteAssignmentId.PREFIX)),
                business, merchant.value, existingDefault, existingCount, contract, command.agreementId, command.revisionNo, site,
                work.now, command.request.actor,
            )
            work.beginWrites()
            // 같은 MASTER 로컬 TX: MID·Default·PRIMARY 연결·entitlement revision·이력·Outbox·요청 결과(구조 경계 §1). 중간 실패는 전체 rollback.
            accounts.insertFirstDefault(issuance)
            merchants.update(merchant.value, merchant.version)
            val account = issuance.account
            val entitlement = merchant.value.entitlementRevision
            work.record(
                AGG_ACCOUNT, account.id.value, 0, "MerchantAccountIssued",
                mapOf(
                    "merchantAccountId" to account.id.value, "merchantId" to account.merchantId.value, "businessId" to account.businessId.value,
                    "issuingContractId" to account.issuingBasis.contractId.value,
                    "issuingAgreementId" to account.issuingBasis.agreementId.value,
                    "issuingRevisionNo" to account.issuingBasis.revisionNo, "lifecycle" to account.lifecycle.name,
                    "entitlementRevision" to entitlement,
                ),
            )
            work.record(
                AGG_MERCHANT, merchant.value.id.value, entitlement, "DefaultMerchantAccountDesignated",
                mapOf("merchantId" to merchant.value.id.value, "merchantAccountId" to account.id.value, "entitlementRevision" to entitlement),
            )
            val primary = issuance.primaryAssignment
            work.record(
                AGG_ASSIGNMENT, primary.id.value, 0, "SiteAssignmentStarted",
                mapOf(
                    "assignmentId" to primary.id.value, "merchantAccountId" to primary.merchantAccountId.value,
                    "merchantSiteId" to primary.merchantSiteId.value, "merchantId" to primary.merchantId.value, "role" to primary.role.name,
                    "status" to primary.status.name, "effectiveFrom" to primary.period.from, "effectiveTo" to primary.period.to,
                    "entitlementRevision" to entitlement,
                ),
            )
            work.publishEntitlement(merchant.value)
            account.id.value
        }

    override fun registerPaymentMethodActivation(command: RegisterPaymentMethodActivationCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_PAYMENT_METHOD_ACTIVATION, command.request,
            listOf(command.merchantAccountId.value, command.paymentMethod.name),
        ) { work ->
            val merchantId = accounts.find(command.merchantAccountId)?.merchantId
                ?: throw MasterRuleViolation(MasterRejection.MERCHANT_ACCOUNT_NOT_FOUND)
            val merchant = merchants.lock(merchantId) ?: error("account merchant must exist")
            val account = accounts.lock(command.merchantAccountId) ?: error("account must exist")
            work.lockedNow()
            // 같은 (MID, 수단)에 종료 아닌 개통은 하나(D-MS-003 — 재개통은 이전 행 TERMINATED 뒤 새 행). 경합은 생성 컬럼 UNIQUE → 재판정.
            activations.findOpen(account.id, command.paymentMethod)?.let {
                throw MasterRuleViolation(MasterRejection.ACTIVATION_DUPLICATE, it.value.id.value)
            }
            val (activation, transition) = PaymentMethodActivation.register(
                PaymentMethodActivationId(work.newId(PaymentMethodActivationId.PREFIX)), account, command.paymentMethod, work.now,
                command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            activations.insert(activation, transition, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            work.record(
                AGG_ACTIVATION, activation.id.value, 0, "PaymentMethodActivationRegistered",
                mapOf(
                    "activationId" to activation.id.value, "merchantAccountId" to account.id.value,
                    "paymentMethod" to activation.paymentMethod.name, "entitlementRevision" to merchant.value.entitlementRevision,
                    // D-MD-017: 등록 = UNDER_REVIEW(개통 아님). 소비자는 ACTIVE 전이 전까지 결제를 허용하지 않는다.
                    "status" to activation.status.name,
                ),
            )
            work.publishEntitlement(merchant.value)
            activation.id.value
        }

    /** 잠금 없이 소속 Business를 읽는다(불변 값) — 잠금 순서 business → merchant를 지키기 위해 먼저 안다. */
    private fun merchantBusiness(merchantId: MerchantId): BusinessId =
        merchants.find(merchantId)?.businessId ?: throw MasterRuleViolation(MasterRejection.MERCHANT_NOT_FOUND)

    /** Scope 입력 → 검증된 [ContractScope](Scope Merchant는 당사자 Business 소속·활성, SITES는 그 Merchant의 활성 Site). */
    private fun validScope(input: ScopeInput, party: BusinessId): ContractScope {
        val merchant = merchants.lock(input.merchantId)?.value ?: throw MasterRuleViolation(MasterRejection.MERCHANT_NOT_FOUND)
        val listed = input.siteIds.sortedBy { it.value }.map { sites.find(it) ?: throw MasterRuleViolation(MasterRejection.SITE_NOT_FOUND) }
        val scope = if (input.siteIds.isEmpty()) ContractScope.MerchantWide(merchant.id) else ContractScope.Sites(merchant.id, input.siteIds)
        ContractScope.requireValid(scope, party, merchant, listed)
        return scope
    }

    private fun scopeFields(scope: ScopeInput): List<String?> = listOf(scope.merchantId.value) + scope.siteIds.map { it.value }.sorted()

    /** fingerprint는 저장 정밀도(ms, 버림) 값으로 만든다 — 같은 ms 안의 다른 표기는 같은 요청이다(review m-4). */
    private fun periodFields(period: EffectivePeriod): List<String?> =
        listOf(period.from.truncatedTo(ChronoUnit.MILLIS).toString(), period.to?.truncatedTo(ChronoUnit.MILLIS)?.toString())

    private fun contractPayload(contract: Contract, revisionNo: Int): Map<String, Any?> {
        val revision = checkNotNull(contract.revision(revisionNo))
        val scope = revision.scope
        return mapOf(
            "contractId" to contract.id.value,
            "businessId" to contract.businessId.value,
            "revisionNo" to revisionNo,
            "scopeKind" to if (scope is ContractScope.Sites) "SITES" else "MERCHANT",
            "scopeMerchantId" to scope.merchantId.value,
            "scopeSiteIds" to (scope as? ContractScope.Sites)?.siteIds?.map { it.value }?.sorted(),
            "effectiveFrom" to revision.effectivePeriod.from,
            "effectiveTo" to revision.effectivePeriod.to,
            "approved" to revision.isApproved,
        )
    }

    private companion object {
        const val AGG_BUSINESS = "Business"
        const val AGG_MERCHANT = "Merchant"
        const val AGG_SITE = "MerchantSite"
        const val AGG_CONTRACT = "Contract"
        const val AGG_AGREEMENT = "ContractServiceAgreement"
        const val AGG_ACCOUNT = "MerchantAccount"
        const val AGG_ASSIGNMENT = "SiteAssignment"
        const val AGG_ACTIVATION = "PaymentMethodActivation"
    }
}
