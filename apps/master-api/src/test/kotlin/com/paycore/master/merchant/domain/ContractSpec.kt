package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.MasterFixture.operator
import com.paycore.master.merchant.domain.MasterFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 계약 구조: Contract(당사자 Business·Scope·Revision·승인/효력) 1:N ContractServiceAgreement(D-MD-001), CSA 1건 = 서비스 종류 1개(D-MD-002),
 * 당사자 = Business·Scope MERCHANT/SITES·Revision 내용 불변·승인 사실 append-only(D-MD-004), MID 발급 근거는 승인된 Revision(초기·INV-17).
 */
class ContractSpec : BehaviorSpec({

    fun violation(block: () -> Unit) = shouldThrow<MasterRuleViolation>(block).rejection

    Given("a contract registered for a Merchant of its party Business") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)

        Then("it starts with revision 1, not approved, and no service agreement") {
            val contract = MasterFixture.contract(merchant, business)
            contract.businessId shouldBe business.id
            contract.revisions.map { it.revisionNo to it.isApproved } shouldBe listOf(1 to false)
            contract.agreements shouldBe emptyList()
        }

        Then("new revisions are numbered consecutively and approval is recorded once (a second approval is rejected)") {
            val contract = MasterFixture.contract(merchant, business)
            contract.registerRevision(ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0.plusSeconds(60)), t0, operator).revisionNo shouldBe 2
            contract.approve(2, t0, operator)
            contract.revision(2)!!.approval shouldBe RevisionApproval(t0, operator)
            violation { contract.approve(2, t0.plusSeconds(1), operator) } shouldBe MasterRejection.REVISION_ALREADY_APPROVED
            contract.revision(2)!!.approval shouldBe RevisionApproval(t0, operator)
            violation { contract.approve(9, t0, operator) } shouldBe MasterRejection.REVISION_NOT_FOUND
        }

        Then("one agreement per service kind: a second CARD_PG_PAYMENT agreement is rejected with the existing one as reference") {
            val contract = MasterFixture.contract(merchant, business)
            val first = contract.addAgreement(MasterFixture.agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
            val error = shouldThrow<MasterRuleViolation> {
                contract.addAgreement(MasterFixture.agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
            }
            (error.rejection to error.reference) shouldBe (MasterRejection.SERVICE_KIND_DUPLICATE to first.id.value)
            contract.agreements shouldBe listOf(first)
        }

        Then("a terminated party Business cannot register a contract") {
            val stored = Business.reconstitute(
                MasterFixture.businessId(), MasterFixture.registrationNumber(), DisplayName("Closed"), Lifecycle.TERMINATED, t0, operator,
            )
            violation {
                Contract.register(MasterFixture.contractId(), stored, ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0), t0, operator)
            } shouldBe MasterRejection.BUSINESS_NOT_ACTIVE
        }
    }

    Given("scope validation (D-MD-004: scope Merchant belongs to the party Business, SITES lists that Merchant's sites)") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val site = MasterFixture.site(merchant)

        Then("a MERCHANT scope of the party's Merchant and a SITES scope of its own site are valid") {
            ContractScope.requireValid(ContractScope.MerchantWide(merchant.id), business.id, merchant, emptyList())
            ContractScope.requireValid(ContractScope.Sites(merchant.id, setOf(site.id)), business.id, merchant, listOf(site))
        }

        Then("a Merchant of another Business is rejected") {
            val stranger = MasterFixture.merchant()
            violation {
                ContractScope.requireValid(ContractScope.MerchantWide(stranger.id), business.id, stranger, emptyList())
            } shouldBe MasterRejection.SCOPE_MERCHANT_OF_OTHER_BUSINESS
        }

        Then("a SITES scope listing another Merchant's site is rejected; an empty SITES scope cannot exist") {
            val sibling = MasterFixture.merchant(business)
            val siblingSite = MasterFixture.site(sibling)
            violation {
                ContractScope.requireValid(ContractScope.Sites(merchant.id, setOf(siblingSite.id)), business.id, merchant, listOf(siblingSite))
            } shouldBe MasterRejection.SCOPE_SITE_OF_OTHER_MERCHANT
            shouldThrow<IllegalArgumentException> { ContractScope.Sites(merchant.id, emptySet()) }
        }

        Then("a terminated scope Merchant is rejected") {
            val closed = MasterFixture.terminatedMerchantAsStored(business)
            violation {
                ContractScope.requireValid(ContractScope.MerchantWide(closed.id), business.id, closed, emptyList())
            } shouldBe MasterRejection.MERCHANT_NOT_ACTIVE
        }
    }

    Given("the applicable revision at an instant (D-MD-011: highest-numbered approved revision whose period contains it)") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val siteA = MasterFixture.site(merchant)
        val siteB = MasterFixture.site(merchant)
        val t5 = t0.plusSeconds(5)
        val t6 = t0.plusSeconds(6)

        /** Rev1 MERCHANT [t0, ∞) 승인, Rev2 SITES{siteA} [t5, ∞) 승인(리뷰 재현). */
        fun superseded(): Contract = MasterFixture.approvedCardContract(merchant, business).also {
            it.registerRevision(ContractScope.Sites(merchant.id, setOf(siteA.id)), EffectivePeriod(t5), t0, operator)
            it.approve(2, t0, operator)
        }

        Then("before t5 Rev1 applies; from t5 Rev2 supersedes Rev1 even though Rev1 has not ended; an unapproved later revision never applies") {
            val contract = superseded()
            contract.registerRevision(ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0), t0, operator) // Rev3, 미승인
            mapOf(
                "t0" to contract.applicableRevisionAt(t0)?.revisionNo,
                "just before t5" to contract.applicableRevisionAt(t5.minusMillis(1))?.revisionNo,
                "t5" to contract.applicableRevisionAt(t5)?.revisionNo,
                "t6" to contract.applicableRevisionAt(t6)?.revisionNo,
                "before t0" to contract.applicableRevisionAt(t0.minusMillis(1))?.revisionNo,
            ) shouldBe mapOf("t0" to 1, "just before t5" to 1, "t5" to 2, "t6" to 2, "before t0" to null)
        }

        Then("reviewer repro: at t6 citing the superseded Rev1 with site B is rejected (D-MD-012); Rev2 with site B is outside scope; Rev2 with site A is the basis") {
            val contract = superseded()
            val agreement = contract.agreements.single().id
            violation { contract.issuingBasis(agreement, 1, siteB, t6) } shouldBe MasterRejection.REVISION_NOT_APPLICABLE
            violation { contract.issuingBasis(agreement, 2, siteB, t6) } shouldBe MasterRejection.SITE_OUTSIDE_CONTRACT_SCOPE
            contract.issuingBasis(agreement, 2, siteA, t6) shouldBe IssuingBasis(contract.id, agreement, 2)
        }
    }

    Given("pre-issuance before effectivity (D-MD-015) and its boundary with D-MD-012") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val site = MasterFixture.site(merchant)
        val t10 = t0.plusSeconds(10)

        /** 승인된 미래 Revision 1([start, ∞))만 있는 계약. */
        fun futureContract(start: java.time.Instant = t10): Contract = MasterFixture.approvedCardContract(merchant, business, EffectivePeriod(start))

        Then("(1) no revision applies now and the cited future revision will be the applicable one at its start → allowed") {
            val contract = futureContract()
            contract.applicableRevisionAt(t0) shouldBe null
            contract.issuingBasis(contract.agreements.single().id, 1, site, t0) shouldBe IssuingBasis(contract.id, contract.agreements.single().id, 1)
        }

        Then("(2) a future revision shadowed at its start by a higher-numbered approved revision with the same or an earlier start → rejected") {
            val sameStart = futureContract().also {
                it.registerRevision(ContractScope.MerchantWide(merchant.id), EffectivePeriod(t10), t0, operator)
                it.approve(2, t0, operator)
            }
            val earlierStart = futureContract().also {
                it.registerRevision(ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0.plusSeconds(5)), t0, operator)
                it.approve(2, t0, operator)
            }
            listOf(sameStart, earlierStart).map { c -> violation { c.issuingBasis(c.agreements.single().id, 1, site, t0) } } shouldBe
                listOf(MasterRejection.REVISION_NOT_APPLICABLE, MasterRejection.REVISION_NOT_APPLICABLE)
            // 가리는 쪽(Rev2)은 자기 시작 시각에 적용되므로 선발급 근거가 된다.
            sameStart.issuingBasis(sameStart.agreements.single().id, 2, site, t0).revisionNo shouldBe 2
        }

        Then("(3) when a revision is applicable now, citing a future revision is rejected (D-MD-012 still governs)") {
            val contract = MasterFixture.approvedCardContract(merchant, business).also {
                it.registerRevision(ContractScope.MerchantWide(merchant.id), EffectivePeriod(t10), t0, operator)
                it.approve(2, t0, operator)
            }
            contract.applicableRevisionAt(t0)?.revisionNo shouldBe 1
            violation { contract.issuingBasis(contract.agreements.single().id, 2, site, t0) } shouldBe MasterRejection.REVISION_NOT_APPLICABLE
            contract.issuingBasis(contract.agreements.single().id, 1, site, t0).revisionNo shouldBe 1
        }

        Then("(4) an unapproved future revision is rejected") {
            val contract = MasterFixture.contract(merchant, business, EffectivePeriod(t10)).also {
                it.addAgreement(MasterFixture.agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
            }
            violation { contract.issuingBasis(contract.agreements.single().id, 1, site, t0) } shouldBe MasterRejection.REVISION_NOT_APPROVED
        }
    }

    Given("revision registration and approval rules (D-MD-013, D-MD-014)") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)

        Then("a revision targeting another Merchant (even of the same Business) is rejected: another Merchant needs a new contract") {
            val contract = MasterFixture.contract(merchant, business)
            val sibling = MasterFixture.merchant(business)
            violation { contract.registerRevision(ContractScope.MerchantWide(sibling.id), EffectivePeriod(t0), t0, operator) } shouldBe
                MasterRejection.CONTRACT_MERCHANT_MISMATCH
            contract.revisions.size shouldBe 1
            contract.merchantId shouldBe merchant.id
        }

        Then("approving a revision whose period has already ended is rejected; approving before the end is accepted") {
            val contract = MasterFixture.contract(merchant, business, EffectivePeriod(t0, t0.plusSeconds(60)))
            violation { contract.approve(1, t0.plusSeconds(60), operator) } shouldBe MasterRejection.REVISION_ENDED
            contract.revision(1)!!.isApproved shouldBe false
            contract.approve(1, t0.plusSeconds(59), operator)
            contract.revision(1)!!.isApproved shouldBe true
        }

        Then("a stored contract whose revisions target different Merchants cannot be restored (mapper guard)") {
            val other = MasterFixture.merchant(business)
            val revisions = listOf(
                ContractRevision.reconstitute(1, ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0), t0, operator, null),
                ContractRevision.reconstitute(2, ContractScope.MerchantWide(other.id), EffectivePeriod(t0), t0, operator, null),
            )
            shouldThrow<IllegalArgumentException> {
                Contract.reconstitute(MasterFixture.contractId(), business.id, merchant.id, revisions, emptyList(), t0, operator)
            }
        }
    }

    Given("the issuing basis of a MID (approved revision, agreement of this contract, site within scope)") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val site = MasterFixture.site(merchant)

        Then("pre-issuance (D-MD-015): an approved revision that has not started yet and will be applicable at its own start is a valid basis") {
            val future = MasterFixture.approvedCardContract(merchant, business, EffectivePeriod(t0.plusSeconds(86_400)))
            future.issuingBasis(future.agreements.single().id, 1, site, t0) shouldBe IssuingBasis(future.id, future.agreements.single().id, 1)
        }

        Then("a registered but not approved revision is rejected") {
            val contract = MasterFixture.contract(merchant, business)
            val agreement = contract.addAgreement(MasterFixture.agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
            violation { contract.issuingBasis(agreement.id, 1, site, t0) } shouldBe MasterRejection.REVISION_NOT_APPROVED
        }

        Then("an approved revision whose effectivity already ended is rejected") {
            val ended = MasterFixture.approvedCardContract(merchant, business, EffectivePeriod(t0, t0.plusSeconds(60)))
            violation { ended.issuingBasis(ended.agreements.single().id, 1, site, t0.plusSeconds(60)) } shouldBe MasterRejection.REVISION_ENDED
        }

        Then("an agreement of another contract or an unknown revision is rejected") {
            val contract = MasterFixture.approvedCardContract(merchant, business)
            violation { contract.issuingBasis(MasterFixture.agreementId(), 1, site, t0) } shouldBe MasterRejection.AGREEMENT_NOT_FOUND
            violation { contract.issuingBasis(contract.agreements.single().id, 2, site, t0) } shouldBe MasterRejection.REVISION_NOT_FOUND
        }

        Then("a site outside a SITES scope is rejected; a listed site is accepted") {
            val other = MasterFixture.site(merchant)
            val contract = Contract.register(
                MasterFixture.contractId(), business, ContractScope.Sites(merchant.id, setOf(site.id)), EffectivePeriod(t0), t0, operator,
            )
            contract.approve(1, t0, operator)
            val agreement = contract.addAgreement(MasterFixture.agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
            violation { contract.issuingBasis(agreement.id, 1, other, t0) } shouldBe MasterRejection.SITE_OUTSIDE_CONTRACT_SCOPE
            contract.issuingBasis(agreement.id, 1, site, t0).revisionNo shouldBe 1
        }
    }
})
