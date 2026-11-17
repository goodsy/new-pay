package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.MasterFixture.operator
import com.paycore.master.merchant.domain.MasterFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
                MasterFixture.businessId(), MasterFixture.registrationNumber(), DisplayName("Closed"), null, BusinessRegistrationStatus.DRAFT, // BUSINESS-PROFILE-001: 복원 인자 확장(기대값 불변)
                BusinessVerificationStatus.NOT_CHECKED, Lifecycle.TERMINATED, t0, operator, t0, operator, 0,
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

    Given("D-MOPS-006 ⑤ / MERCHANT-OPS-002 §3.4: a contract may not start before the KST date of the decision time (CONTRACT_START_RETROACTIVE)") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)

        /** Named Fixture: 시각 [at]에 판정되는, [from]에 시작하는 MERCHANT Scope 계약 등록(종료 없음). */
        fun registerStartingAt(from: String, at: String, party: Business = business): Contract = Contract.register(
            MasterFixture.contractId(), party, ContractScope.MerchantWide(merchant.id), EffectivePeriod(java.time.Instant.parse(from)), java.time.Instant.parse(at), operator,
        )
        val kstMidnightOfNov9 = "2026-11-08T15:00:00Z" // KST 11-09 00:00
        val kstLastMilliOfNov8 = "2026-11-08T14:59:59.999Z" // KST 11-08 23:59:59.999

        Then("KST boundary table: same KST day (from its 00:00 on) and future starts are accepted; one millisecond before the KST midnight of the decision date is rejected") {
            data class Case(val name: String, val at: String, val from: String, val accepted: Boolean)
            val cases = listOf(
                Case("at KST 11-09 00:00, from KST 11-09 00:00", kstMidnightOfNov9, kstMidnightOfNov9, true),
                Case("at KST 11-09 00:00, from KST 11-08 23:59:59.999", kstMidnightOfNov9, kstLastMilliOfNov8, false),
                Case("at KST 11-08 23:59:59.999, from KST 11-08 00:00 (same day, earlier time)", kstLastMilliOfNov8, "2026-11-07T15:00:00Z", true),
                Case("at KST 11-08 23:59:59.999, from KST 11-07 23:59:59.999", kstLastMilliOfNov8, "2026-11-07T14:59:59.999Z", false),
                Case("at KST 11-09 00:00, from a future day", kstMidnightOfNov9, "2026-11-10T15:00:00Z", true),
                Case("at KST 11-09 00:00, from later the same day", kstMidnightOfNov9, "2026-11-09T03:00:00Z", true),
            )
            cases.forEach { c ->
                withClue(c.name) {
                    if (c.accepted) {
                        registerStartingAt(c.from, c.at).revisions.single().effectivePeriod.from shouldBe java.time.Instant.parse(c.from)
                    } else {
                        violation { registerStartingAt(c.from, c.at) } shouldBe MasterRejection.CONTRACT_START_RETROACTIVE
                    }
                }
            }
        }

        Then("an inactive party is still rejected with BUSINESS_NOT_ACTIVE first (the retroactive check comes after party.requireActive)") {
            val closed = Business.reconstitute(
                MasterFixture.businessId(), MasterFixture.registrationNumber(), DisplayName("Closed"), null, BusinessRegistrationStatus.DRAFT, // BUSINESS-PROFILE-001: 복원 인자 확장(기대값 불변)
                BusinessVerificationStatus.NOT_CHECKED, Lifecycle.TERMINATED, t0, operator, t0, operator, 0,
            )
            violation { registerStartingAt("2026-11-01T15:00:00Z", kstMidnightOfNov9, closed) } shouldBe MasterRejection.BUSINESS_NOT_ACTIVE
        }

        Then("D-MOPS-007 ② (Q-MOPS-18 answered — expectation reversed from the B1 n-1 boundary): a later revision with a past start is rejected with CONTRACT_START_RETROACTIVE") {
            val contract = registerStartingAt(kstMidnightOfNov9, kstMidnightOfNov9)
            val past = EffectivePeriod(java.time.Instant.parse("2026-11-01T15:00:00Z"))
            violation {
                contract.registerRevision(ContractScope.MerchantWide(merchant.id), past, java.time.Instant.parse(kstMidnightOfNov9), operator)
            } shouldBe MasterRejection.CONTRACT_START_RETROACTIVE
            contract.revisions.size shouldBe 1
        }
    }

    Given("D-MOPS-007 ② / MERCHANT-OPS-002 §9.2 (B2-1): a contract revision may not start before the KST date of the decision time") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val sibling = MasterFixture.merchant(business)
        val kstMidnightOfNov9 = "2026-11-08T15:00:00Z" // KST 11-09 00:00
        val kstLastMilliOfNov8 = "2026-11-08T14:59:59.999Z" // KST 11-08 23:59:59.999

        /** Named Fixture: KST 11-07 00:00에 시작한 Revision 1만 있는 계약(그 날 판정 — 소급 아님). */
        fun contractStartedOnNov7(): Contract = Contract.register(
            MasterFixture.contractId(), business, ContractScope.MerchantWide(merchant.id),
            EffectivePeriod(java.time.Instant.parse("2026-11-06T15:00:00Z")), java.time.Instant.parse("2026-11-06T15:00:00Z"), operator,
        )

        /** Named Fixture: 시각 [at]에 판정되는, [from]에 시작하는 MERCHANT Scope Revision 등록. */
        fun registerRevisionStartingAt(contract: Contract, from: String, at: String, scopeMerchant: Merchant = merchant): ContractRevision =
            contract.registerRevision(
                ContractScope.MerchantWide(scopeMerchant.id), EffectivePeriod(java.time.Instant.parse(from)), java.time.Instant.parse(at), operator,
            )

        Then("KST boundary table (same values as Revision 1): same KST day and future starts accepted, one millisecond before the decision date's KST midnight rejected") {
            data class Case(val name: String, val at: String, val from: String, val accepted: Boolean)
            val cases = listOf(
                Case("at KST 11-09 00:00, from KST 11-09 00:00", kstMidnightOfNov9, kstMidnightOfNov9, true),
                Case("at KST 11-09 00:00, from KST 11-08 23:59:59.999", kstMidnightOfNov9, kstLastMilliOfNov8, false),
                Case("at KST 11-08 23:59:59.999, from KST 11-08 00:00 (same day, earlier time)", kstLastMilliOfNov8, "2026-11-07T15:00:00Z", true),
                Case("at KST 11-08 23:59:59.999, from KST 11-07 23:59:59.999", kstLastMilliOfNov8, "2026-11-07T14:59:59.999Z", false),
                Case("at KST 11-09 00:00, from a future day", kstMidnightOfNov9, "2026-11-10T15:00:00Z", true),
            )
            cases.forEach { c ->
                withClue(c.name) {
                    val contract = contractStartedOnNov7()
                    if (c.accepted) {
                        registerRevisionStartingAt(contract, c.from, c.at).revisionNo shouldBe 2
                    } else {
                        violation { registerRevisionStartingAt(contract, c.from, c.at) } shouldBe MasterRejection.CONTRACT_START_RETROACTIVE
                        contract.revisions.size shouldBe 1
                    }
                }
            }
        }

        Then("a revision targeting another merchant is rejected with CONTRACT_MERCHANT_MISMATCH before the retroactive check") {
            violation { registerRevisionStartingAt(contractStartedOnNov7(), "2026-11-01T15:00:00Z", kstMidnightOfNov9, sibling) } shouldBe
                MasterRejection.CONTRACT_MERCHANT_MISMATCH
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

    Given("D-MOPS-008 ③ / MERCHANT-OPS-002 §9.3 판정 5 (B2-2, C-27·C-31): the evidence path refuses an approver who registered the revision") {
        val business = MasterFixture.business()
        val merchant = MasterFixture.merchant(business)
        val approver = ApprovalEvidenceFixtures.APPROVER
        val revisionRegistrant = ApprovalEvidenceFixtures.OTHER

        Then("Revision 1: the contract registrant approving with evidence is APPROVER_IS_REVISION_REGISTRANT and nothing is recorded; another approver is recorded") {
            val contract = Contract.register(MasterFixture.contractId(), business, ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0), t0, approver)
            violation { contract.approveWithEvidence(1, t0, approver) } shouldBe MasterRejection.APPROVER_IS_REVISION_REGISTRANT
            contract.revision(1)!!.isApproved shouldBe false
            contract.approveWithEvidence(1, t0, revisionRegistrant) shouldBe RevisionApproval(t0, revisionRegistrant)
        }

        Then("Revision 2: its own registrant (not the contract registrant) is the one refused") {
            val contract = MasterFixture.contract(merchant, business)
            contract.registerRevision(ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0.plusSeconds(60)), t0, revisionRegistrant)
            violation { contract.approveWithEvidence(2, t0, revisionRegistrant) } shouldBe MasterRejection.APPROVER_IS_REVISION_REGISTRANT
            contract.approveWithEvidence(2, t0, operator).approvedBy shouldBe operator
        }

        Then("order: REVISION_NOT_FOUND, then REVISION_ENDED before the registrant check, then the registrant check before REVISION_ALREADY_APPROVED") {
            val ended = Contract.register(
                MasterFixture.contractId(), business, ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0, t0.plusSeconds(60)), t0, approver,
            )
            violation { ended.approveWithEvidence(9, t0, approver) } shouldBe MasterRejection.REVISION_NOT_FOUND
            violation { ended.approveWithEvidence(1, t0.plusSeconds(60), approver) } shouldBe MasterRejection.REVISION_ENDED

            val approved = Contract.register(MasterFixture.contractId(), business, ContractScope.MerchantWide(merchant.id), EffectivePeriod(t0), t0, approver)
            approved.approve(1, t0, operator)
            violation { approved.approveWithEvidence(1, t0, approver) } shouldBe MasterRejection.APPROVER_IS_REVISION_REGISTRANT
            violation { approved.approveWithEvidence(1, t0, revisionRegistrant) } shouldBe MasterRejection.REVISION_ALREADY_APPROVED
        }

        Then("C-31: the evidence-less approve (Demo seed path) still lets the registrant approve — no registrant check there") {
            val contract = MasterFixture.contract(merchant, business)
            contract.approve(1, t0, operator) shouldBe RevisionApproval(t0, operator)
        }
    }
})
