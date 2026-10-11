package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.MasterFixture.operator
import com.paycore.master.merchant.domain.MasterFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * persistence Mapper 전용 복원 경로(`reconstitute`)가 domain 규칙상 존재할 수 없는 저장 상태를 거부하는지(review m-6, 명세 §4.1).
 * Invalid 입력은 의도를 드러내는 이름으로 명시한다(Test Fixture Policy 7) — 정상 Fixture가 아니다.
 */
class MasterReconstitutionSpec : BehaviorSpec({

    val issued = MasterFixture.issued()
    val primary = issued.issuance.primaryAssignment

    fun restoreAssignment(status: SiteAssignmentStatus, period: EffectivePeriod, createdAt: java.time.Instant) =
        SiteAssignment.reconstitute(
            primary.id, primary.merchantAccountId, primary.merchantSiteId, primary.merchantId, primary.role, status, period, createdAt, operator,
        )

    Given("stored site assignments") {
        Then("a stored row equal to the issued PRIMARY is restored unchanged") {
            restoreAssignment(primary.status, primary.period, primary.createdAt).let { listOf(it.id, it.role, it.status, it.period) } shouldBe
                listOf(primary.id, primary.role, primary.status, primary.period)
        }

        Then("invalid stored state: a start before it was recorded (retroactive, D-MD-005) is refused") {
            shouldThrow<IllegalArgumentException> { restoreAssignment(SiteAssignmentStatus.ACTIVE, EffectivePeriod(t0.minusMillis(1)), t0) }
        }

        Then("invalid stored state: ENDED without an end instant is refused; ENDED with an end is restored") {
            shouldThrow<IllegalArgumentException> { restoreAssignment(SiteAssignmentStatus.ENDED, EffectivePeriod(t0), t0) }
            restoreAssignment(SiteAssignmentStatus.ENDED, EffectivePeriod(t0, t0.plusSeconds(1)), t0).status shouldBe SiteAssignmentStatus.ENDED
        }
    }

    Given("stored MIDs") {
        Then("invalid stored state: an issuing basis with revision 0 is refused") {
            shouldThrow<IllegalArgumentException> {
                MerchantAccount.reconstitute(
                    MasterFixture.accountId(), issued.merchant.id, issued.business.id,
                    IssuingBasis(issued.contract.id, issued.contract.agreements.single().id, 0), Lifecycle.ACTIVE, t0, operator,
                )
            }
        }
    }
})
