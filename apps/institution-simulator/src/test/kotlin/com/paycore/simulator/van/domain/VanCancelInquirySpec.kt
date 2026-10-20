package com.paycore.simulator.van.domain

import com.paycore.simulator.van.domain.VanCancelFixture.cancel
import com.paycore.simulator.van.domain.VanCancelFixture.cancelRequestId
import com.paycore.simulator.van.domain.VanCancelFixture.ids
import com.paycore.simulator.van.domain.VanCancelFixture.now
import com.paycore.simulator.van.domain.VanCancelFixture.originalApproved
import com.paycore.simulator.van.domain.VanCancelFixture.request
import com.paycore.simulator.van.domain.VanFixture.profileA
import com.paycore.simulator.van.domain.VanFixture.profileB
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * 가상 VAN 취소 조회(CANCEL-001 §6.5 — AU §5.4 규칙을 취소 namespace에): 원 **취소** 요청 ID 참조만, 결정 FOUND, 종결 표지 NO_EFFECT_CONFIRMED, 무효과 종결은 프로파일
 * 취소 Capability `cancel.confirmsNoEffect`가 있고 `cancel.closableAfter` ≤ 연령일 때만, 보존기간(`cancel.inquiryRetention`)이 지난 요청 ID는 UNAVAILABLE. 취소 PENDING
 * 예약은 없다(CN-23).
 */
class VanCancelInquirySpec : BehaviorSpec({

    val oldEnough = cancelRequestId(age = Duration.ofMinutes(3))
    val tooYoung = cancelRequestId(age = Duration.ofSeconds(30))

    fun namespace(requestId: String) = VanNamespace(VanFixture.CLIENT, "TEST", "SIMMERCH0001", VanNamespace.CANCEL, requestId)

    fun inquire(
        requestId: String,
        existing: VanCancelEntry? = null,
        closeIfAbsent: Boolean = false,
        profile: VanProfile = profileA,
        previousInquiries: Long = 0,
        at: Instant = now,
    ): VanCancelInquiryJudgement = VanCancelInquiry.judge(namespace(requestId), existing, closeIfAbsent, profile, previousInquiries, ids, at)

    fun decision(requestId: String, card: String = "VC-CREDIT-OK"): VanCancelEntry {
        val o = originalApproved(cardRef = card)
        return cancel(request = request(o, requestId = requestId), original = o).shouldBeInstanceOf<VanCancelJudgement.Decide>().entry
    }

    Given("a recorded cancel decision") {
        Then("the inquiry finds it, and closeIfAbsent never closes a decided cancel") {
            val d = decision(oldEnough)
            inquire(oldEnough, existing = d).shouldBeInstanceOf<VanCancelInquiryJudgement.Found>().entry shouldBe d
            inquire(oldEnough, existing = d, closeIfAbsent = true).shouldBeInstanceOf<VanCancelInquiryJudgement.Found>().entry shouldBe d
        }
        Then("VC-CXL-INQUIRY-UNAVAILABLE-ONCE answers UNAVAILABLE only to the first cancel inquiry") {
            val d = decision(oldEnough, card = "VC-CXL-INQUIRY-UNAVAILABLE-ONCE")
            inquire(oldEnough, existing = d) shouldBe VanCancelInquiryJudgement.Unavailable
            inquire(oldEnough, existing = d, previousInquiries = 1).shouldBeInstanceOf<VanCancelInquiryJudgement.Found>()
        }
    }

    Given("no cancel row") {
        Then("profile A closes an old-enough cancel request ID with closeIfAbsent; the tombstone is then NO_EFFECT_CONFIRMED with the same closure ID") {
            val closed = inquire(oldEnough, closeIfAbsent = true).shouldBeInstanceOf<VanCancelInquiryJudgement.Close>().entry
            closed.kind shouldBe VanEntryKind.CLOSURE
            closed.entryId shouldBe ids.closureId
            closed.namespace shouldBe namespace(oldEnough)
            closed.originalRequestId shouldBe null
            inquire(oldEnough, existing = closed).shouldBeInstanceOf<VanCancelInquiryJudgement.NoEffectConfirmed>().entry shouldBe closed
        }
        Then("without closeIfAbsent, before cancel.closableAfter, or on profile B it stays NOT_FOUND (no tombstone)") {
            inquire(oldEnough) shouldBe VanCancelInquiryJudgement.NotFound
            inquire(tooYoung, closeIfAbsent = true) shouldBe VanCancelInquiryJudgement.NotFound
            inquire(oldEnough, closeIfAbsent = true, profile = profileB) shouldBe VanCancelInquiryJudgement.NotFound
        }
        Then("the cancel capability decides, not the authorization one (authorization confirmsNoEffect without cancel.confirmsNoEffect does not close)") {
            val mixed = profileA.copy(cancel = profileA.cancel.copy(confirmsNoEffect = false))
            inquire(oldEnough, closeIfAbsent = true, profile = mixed) shouldBe VanCancelInquiryJudgement.NotFound
            val longer = profileA.copy(cancel = profileA.cancel.copy(closableAfter = Duration.ofMinutes(5)))
            inquire(oldEnough, closeIfAbsent = true, profile = longer) shouldBe VanCancelInquiryJudgement.NotFound
        }
    }

    Given("a cancel request ID older than cancel.inquiryRetention") {
        Then("it is UNAVAILABLE — even with a recorded decision and even with closeIfAbsent (never NOT_FOUND or a closure)") {
            val expired = cancelRequestId(age = profileA.cancel.inquiryRetention.plusSeconds(1))
            val d = VanCancelEntry.closure(namespace(expired), ids.closureId, now)
            inquire(expired, existing = d) shouldBe VanCancelInquiryJudgement.Unavailable
            inquire(expired, closeIfAbsent = true) shouldBe VanCancelInquiryJudgement.Unavailable
        }
    }
})
