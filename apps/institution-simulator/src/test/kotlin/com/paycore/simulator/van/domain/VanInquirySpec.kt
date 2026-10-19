package com.paycore.simulator.van.domain

import com.paycore.simulator.van.domain.VanFixture.authorize
import com.paycore.simulator.van.domain.VanFixture.evidence
import com.paycore.simulator.van.domain.VanFixture.now
import com.paycore.simulator.van.domain.VanFixture.profileA
import com.paycore.simulator.van.domain.VanFixture.profileB
import com.paycore.simulator.van.domain.VanFixture.request
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * 가상 VAN 조회(원 요청 ID — AUTH-001 §5.4): 원장 결정 FOUND, 종결 표지 NO_EFFECT_CONFIRMED, PENDING 예약은 종결하지 않음(H-3), 무효과 종결은 Capability 있는 프로파일 A만·
 * `closableAfter` 이후만, 보존기간이 지난 요청 ID는 UNAVAILABLE(m-7 — NOT_FOUND를 무효과로 오해하지 않게). 요청 ID의 시각은 ULID 시각에서 읽는다.
 */
class VanInquirySpec : BehaviorSpec({

    val oldEnough = VanFixture.requestId(age = Duration.ofMinutes(3))
    val tooYoung = VanFixture.requestId(age = Duration.ofSeconds(30))

    fun inquire(
        requestId: String,
        existing: VanLedgerEntry? = null,
        pending: VanPendingDecision? = null,
        closeIfAbsent: Boolean = false,
        profile: VanProfile = profileA,
        previousInquiries: Long = 0,
        behaviour: VanBehaviour = VanBehaviour.NORMAL,
        at: Instant = now,
    ): VanInquiryJudgement = VanInquiry.judge(
        VanNamespace(VanFixture.CLIENT, "TEST", "SIMMERCH0001", VanNamespace.AUTHORIZE, requestId), existing, pending, closeIfAbsent, profile, previousInquiries,
        behaviour, VanFixture.ids, at,
    )

    Given("a recorded decision") {
        val decision = authorize(request = request(requestId = oldEnough)).shouldBeInstanceOf<VanJudgement.Decide>().entry
        Then("the inquiry finds it, and closeIfAbsent never closes a decided request") {
            inquire(oldEnough, existing = decision).shouldBeInstanceOf<VanInquiryJudgement.Found>().entry shouldBe decision
            inquire(oldEnough, existing = decision, closeIfAbsent = true).shouldBeInstanceOf<VanInquiryJudgement.Found>().entry shouldBe decision
        }
        Then("VC-VAN-INQUIRY-UNAVAILABLE-ONCE answers UNAVAILABLE only to the first inquiry") {
            inquire(oldEnough, existing = decision, behaviour = VanBehaviour.INQUIRY_UNAVAILABLE_ONCE) shouldBe VanInquiryJudgement.Unavailable
            inquire(oldEnough, existing = decision, behaviour = VanBehaviour.INQUIRY_UNAVAILABLE_ONCE, previousInquiries = 1)
                .shouldBeInstanceOf<VanInquiryJudgement.Found>()
        }
    }

    Given("no ledger row and no reservation") {
        Then("profile A closes an old-enough request with closeIfAbsent and confirms no effect") {
            val closed = inquire(oldEnough, closeIfAbsent = true).shouldBeInstanceOf<VanInquiryJudgement.Close>().entry
            closed.kind shouldBe VanEntryKind.CLOSURE
            closed.entryId shouldBe VanFixture.ids.closureId
            inquire(oldEnough, existing = closed).shouldBeInstanceOf<VanInquiryJudgement.NoEffectConfirmed>().entry shouldBe closed
        }
        Then("without closeIfAbsent, before closableAfter, or on profile B it stays NOT_FOUND (no tombstone)") {
            inquire(oldEnough) shouldBe VanInquiryJudgement.NotFound
            inquire(tooYoung, closeIfAbsent = true) shouldBe VanInquiryJudgement.NotFound
            inquire(oldEnough, closeIfAbsent = true, profile = profileB) shouldBe VanInquiryJudgement.NotFound
        }
        Then("a request ID that is not a ULID request ID is never closed") {
            inquire("ar_not-a-ulid-request-id-00000", closeIfAbsent = true) shouldBe VanInquiryJudgement.NotFound
        }
    }

    Given("a request ID older than the inquiry retention (rev 0.3 m-7)") {
        val expired = VanFixture.requestId(age = profileA.inquiryRetention.plusSeconds(1))
        Then("the inquiry is UNAVAILABLE, never NOT_FOUND, and closeIfAbsent creates no tombstone — even when a decision exists") {
            inquire(expired) shouldBe VanInquiryJudgement.Unavailable
            inquire(expired, closeIfAbsent = true) shouldBe VanInquiryJudgement.Unavailable
            // 결정 자체는 보존기간 안에 내려졌다(검토 #2 n-3 — 보존기간 밖 새 요청은 거절되므로 그 시점의 판정으로 만든다).
            val decision = authorize(request = request(requestId = expired), at = now.minus(profileA.inquiryRetention)).shouldBeInstanceOf<VanJudgement.Decide>().entry
            inquire(expired, existing = decision) shouldBe VanInquiryJudgement.Unavailable
        }
    }

    Given("a PENDING reservation (rev 0.3 H-3)") {
        // 예약은 확정되면 상태가 바뀐다 — 행마다 새 예약을 만든다.
        fun pending() = authorize(request = request(requestId = oldEnough), evidence = evidence(request = request(requestId = oldEnough), cardRef = "VC-VAN-PENDING-THEN-APPROVE"))
            .shouldBeInstanceOf<VanJudgement.Hold>().pending
        Then("the inquiry answers PENDING with the pendingId and closeIfAbsent does not close it") {
            inquire(oldEnough, pending = pending()).shouldBeInstanceOf<VanInquiryJudgement.StillPending>().pending.pendingId shouldBe VanFixture.ids.pendingId
            inquire(oldEnough, pending = pending(), closeIfAbsent = true).shouldBeInstanceOf<VanInquiryJudgement.StillPending>()
        }
        Then("after the resolve time the inquiry resolves it into the decision (closeIfAbsent included)") {
            listOf(false, true).forEach { close ->
                val p = pending()
                val r = inquire(oldEnough, pending = p, closeIfAbsent = close, at = p.resolveAfter).shouldBeInstanceOf<VanInquiryJudgement.ResolvePending>()
                r.entry.kind shouldBe VanEntryKind.DECISION
                r.entry.outcome shouldBe VanDecisionOutcome.APPROVED
            }
        }
    }

    Given("the ULID time of a request ID") {
        Then("it is read back to the millisecond") {
            VanUlid.timeOf("ar_" + VanUlid.format(now, 7)) shouldBe now
            VanUlid.timeOf("ar_x") shouldBe null
        }
    }
})
