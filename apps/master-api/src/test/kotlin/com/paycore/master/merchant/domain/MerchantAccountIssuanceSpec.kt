package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.MasterFixture.operator
import com.paycore.master.merchant.domain.MasterFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 최초 Default MID 발급(구조 경계 §1, 초기 §가맹점·계약·MID, D-MD-003·D-MD-004·D-MD-005·D-MD-007) — domain 판정. 원자 저장·동시성은
 * application/DB 시험(IssueFirstDefaultMerchantAccount*Spec)이 본다(domain 단위 시험은 경합의 증거가 아니다).
 */
class MerchantAccountIssuanceSpec : BehaviorSpec({

    fun violation(block: () -> Unit) = shouldThrow<MasterRuleViolation>(block)

    Given("an active Merchant with a site and an approved card contract of its Business") {
        Then("the first MID is ACTIVE, belongs to the Merchant, keeps the issuing agreement and revision, and is the Default") {
            val issued = MasterFixture.issued()
            val account = issued.issuance.account
            account.lifecycle shouldBe Lifecycle.ACTIVE
            account.merchantId shouldBe issued.merchant.id
            account.businessId shouldBe issued.business.id
            account.issuingBasis shouldBe IssuingBasis(issued.contract.id, issued.contract.agreements.single().id, 1)
            issued.issuance.designation shouldBe DefaultDesignation(issued.merchant.id, account.id, t0, operator)
        }

        Then("the first link is PRIMARY, ACTIVE, starts at the issuance instant and has no scheduled end") {
            val issued = MasterFixture.issued()
            val primary = issued.issuance.primaryAssignment
            listOf(primary.role, primary.status, primary.period, primary.merchantSiteId, primary.merchantId) shouldBe
                listOf(SiteAssignmentRole.PRIMARY, SiteAssignmentStatus.ACTIVE, EffectivePeriod(t0), issued.site.id, issued.merchant.id)
        }

        Then("the Merchant's entitlement revision advances by one (a payment-eligibility input changed)") {
            MasterFixture.issued().merchant.entitlementRevision shouldBe 1
        }

        Then("pre-issuance before the contract becomes effective is allowed on the revision that will apply at its start (D-MD-015); payments stay blocked elsewhere") {
            val business = MasterFixture.business()
            val merchant = MasterFixture.merchant(business)
            val site = MasterFixture.site(merchant)
            val contract = MasterFixture.approvedCardContract(merchant, business, EffectivePeriod(t0.plusSeconds(86_400 * 30L)))
            val issuance = MerchantAccountIssuance.issueFirstDefault(
                MasterFixture.accountId(), MasterFixture.assignmentId(), business, merchant, null, 0, contract,
                contract.agreements.single().id, 1, site, t0, operator,
            )
            issuance.account.lifecycle shouldBe Lifecycle.ACTIVE
            issuance.account.issuingBasis.revisionNo shouldBe 1
            merchant.entitlementRevision shouldBe 1
        }
    }

    Given("the Merchant already has a MID (the first Default exists)") {
        Then("another first-Default issuance is rejected with the existing Default as reference") {
            val issued = MasterFixture.issued()
            val error = violation {
                MerchantAccountIssuance.issueFirstDefault(
                    MasterFixture.accountId(), MasterFixture.assignmentId(), issued.business, issued.merchant, issued.issuance.account.id, 1,
                    issued.contract, issued.contract.agreements.single().id, 1, MasterFixture.site(issued.merchant), t0, operator,
                )
            }
            (error.rejection to error.reference) shouldBe (MasterRejection.DEFAULT_ALREADY_ISSUED to issued.issuance.account.id.value)
        }
    }

    Given("inputs that break the confirmed relations") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val site = MasterFixture.site(merchant)

        fun issue(
            contract: Contract,
            primarySite: MerchantSite = site,
            m: Merchant = merchant,
            revisionNo: Int = 1,
        ) = MerchantAccountIssuance.issueFirstDefault(
            MasterFixture.accountId(), MasterFixture.assignmentId(), business, m, null, 0, contract,
            contract.agreements.single().id, revisionNo, primarySite, t0, operator,
        )

        Then("a contract whose party is another Business is rejected") {
            val strangerBusiness = MasterFixture.business()
            val strangerMerchant = MasterFixture.merchant(strangerBusiness)
            violation { issue(MasterFixture.approvedCardContract(strangerMerchant, strangerBusiness)) }.rejection shouldBe
                MasterRejection.CONTRACT_PARTY_MISMATCH
        }

        Then("a contract of another Merchant of the same Business is rejected (D-MD-013: the contract targets one Merchant)") {
            val sibling = MasterFixture.merchant(business)
            val siblingSite = MasterFixture.site(sibling)
            violation {
                MerchantAccountIssuance.issueFirstDefault(
                    MasterFixture.accountId(), MasterFixture.assignmentId(), business, sibling, null, 0,
                    MasterFixture.approvedCardContract(merchant, business).let { c -> c }, MasterFixture.agreementId(), 1, siblingSite, t0, operator,
                )
            }.rejection shouldBe MasterRejection.CONTRACT_MERCHANT_MISMATCH
        }

        Then("a primary site of another Merchant (even of the same Business) is rejected") {
            val sibling = MasterFixture.merchant(business)
            violation { issue(MasterFixture.approvedCardContract(merchant, business), MasterFixture.site(sibling)) }.rejection shouldBe
                MasterRejection.SITE_OF_OTHER_MERCHANT
        }

        Then("a not approved revision is rejected (Production MID requires contract approval, D-MD-003)") {
            val contract = MasterFixture.contract(merchant, business)
            contract.addAgreement(MasterFixture.agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
            violation { issue(contract) }.rejection shouldBe MasterRejection.REVISION_NOT_APPROVED
        }

        Then("a primary site outside the SITES scope of the revision is rejected") {
            val listed = MasterFixture.site(merchant)
            val contract = Contract.register(
                MasterFixture.contractId(), business, ContractScope.Sites(merchant.id, setOf(listed.id)), EffectivePeriod(t0), t0, operator,
            ).also {
                it.approve(1, t0, operator)
                it.addAgreement(MasterFixture.agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
            }
            violation { issue(contract) }.rejection shouldBe MasterRejection.SITE_OUTSIDE_CONTRACT_SCOPE
        }

        Then("a terminated Merchant (as stored) is rejected") {
            val closed = MasterFixture.terminatedMerchantAsStored(business)
            violation { issue(MasterFixture.approvedCardContract(merchant, business), m = closed) }.rejection shouldBe
                MasterRejection.MERCHANT_NOT_ACTIVE
        }
    }
})
