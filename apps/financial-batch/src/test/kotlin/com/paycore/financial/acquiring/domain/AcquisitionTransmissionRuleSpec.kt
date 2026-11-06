package com.paycore.financial.acquiring.domain

import com.paycore.financial.acquiring.domain.AcquisitionFixture.CUTOFF
import com.paycore.financial.acquiring.domain.AcquisitionFixture.header
import com.paycore.financial.acquiring.domain.AcquisitionFixture.included
import com.paycore.financial.acquiring.domain.AcquisitionFixture.sale
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * 전송 축 전이 규칙(ACQUIRING-001 §4.1 — 2-D, AQ-TD1..TD5). 점유·lease·복귀 상태·결과별 상태를 명시 Fixture로 본다(봉인된 파일 = 실제 봉인 Factory).
 */
class AcquisitionTransmissionRuleSpec : BehaviorSpec({

    val now = Instant.parse("2026-11-05T14:10:00.000Z")
    val lease = now.plusSeconds(120)
    val sealed = AcquisitionFile.seal(header(), CUTOFF, listOf(included(sale()))).first

    fun claimed(from: AcquisitionFile = sealed, at: Instant = now): TransmissionClaim.Claimed =
        from.claimTransmission(at, at.plusSeconds(120)) as TransmissionClaim.Claimed

    Given("AQ-TD1 a SEALED file") {
        Then("claiming holds a lease, keeps the version (the store bumps it) and resumes to SEALED") {
            val c = claimed()
            c.resumeState shouldBe TransmissionState.SEALED
            listOf(c.file.transmissionState, c.file.transmissionLeaseUntil, c.file.transmittedAt, c.file.version) shouldBe
                listOf(TransmissionState.TRANSMITTING, lease, null, 0L)
        }
        Then("a lease that does not end after the claim is a programming error") {
            shouldThrow<AcquisitionContractViolation> { sealed.claimTransmission(now, now) }
        }
        Then("only a TRANSMITTING file completes a transmission") {
            shouldThrow<AcquisitionContractViolation> {
                sealed.completeTransmission(TransmissionOutcome.Delivered(TransmissionEvidence.RENAMED), TransmissionState.SEALED, now)
            }
        }
    }

    Given("AQ-TD2 a TRANSMITTING file and each attempt outcome") {
        val c = claimed()
        Then("delivered → TRANSMITTED with the transmission time and no lease") {
            val f = c.file.completeTransmission(TransmissionOutcome.Delivered(TransmissionEvidence.RENAMED_AND_VERIFIED), c.resumeState, now)
            listOf(f.transmissionState, f.transmissionLeaseUntil, f.transmittedAt) shouldBe listOf(TransmissionState.TRANSMITTED, null, now)
        }
        Then("not delivered → the resume state (SEALED), unknown → TRANSMISSION_UNKNOWN, content mismatch → TRANSMISSION_QUARANTINED") {
            c.file.completeTransmission(TransmissionOutcome.NotDelivered(TransmissionFailureReason.UPLOAD_FAILED), c.resumeState, now).transmissionState shouldBe
                TransmissionState.SEALED
            c.file.completeTransmission(TransmissionOutcome.Unknown(TransmissionFailureReason.RENAME_OUTCOME_UNKNOWN), c.resumeState, now).transmissionState shouldBe
                TransmissionState.TRANSMISSION_UNKNOWN
            c.file.completeTransmission(TransmissionOutcome.ContentMismatch, c.resumeState, now).transmissionState shouldBe TransmissionState.TRANSMISSION_QUARANTINED
        }
        Then("the resume state is SEALED or TRANSMISSION_UNKNOWN only") {
            shouldThrow<AcquisitionContractViolation> {
                c.file.completeTransmission(TransmissionOutcome.NotDelivered(TransmissionFailureReason.UPLOAD_FAILED), TransmissionState.TRANSMITTED, now)
            }
        }
    }

    Given("AQ-TD3 a TRANSMITTING file whose lease has or has not expired") {
        val c = claimed()
        Then("within the lease it is IN_FLIGHT") {
            c.file.claimTransmission(now.plusSeconds(119), now.plusSeconds(240)) shouldBe TransmissionClaim.NotClaimable(NotClaimableReason.IN_FLIGHT)
        }
        Then("at or after the lease end another attempt takes over and resumes to TRANSMISSION_UNKNOWN (the earlier attempt may have renamed)") {
            val takeover = c.file.claimTransmission(lease, lease.plusSeconds(120))
            takeover.shouldBeInstanceOf<TransmissionClaim.Claimed>()
            takeover.resumeState shouldBe TransmissionState.TRANSMISSION_UNKNOWN
        }
    }

    Given("AQ-TD4 a TRANSMISSION_UNKNOWN file") {
        val unknown = claimed().file.completeTransmission(TransmissionOutcome.Unknown(TransmissionFailureReason.RENAME_OUTCOME_UNKNOWN), TransmissionState.SEALED, now)
        Then("it is claimable for verification and a not-delivered result never falls back to SEALED (NOT_FOUND alone is no proof)") {
            val c = claimed(unknown, now.plusSeconds(300))
            c.resumeState shouldBe TransmissionState.TRANSMISSION_UNKNOWN
            c.file.completeTransmission(TransmissionOutcome.NotDelivered(TransmissionFailureReason.UPLOAD_FAILED), c.resumeState, now).transmissionState shouldBe
                TransmissionState.TRANSMISSION_UNKNOWN
        }
    }

    Given("AQ-TD5 terminal transmission states") {
        val c = claimed()
        Then("TRANSMITTED and TRANSMISSION_QUARANTINED are not claimed again (same file id, no new transmission)") {
            c.file.completeTransmission(TransmissionOutcome.Delivered(TransmissionEvidence.RENAMED), c.resumeState, now).claimTransmission(now, lease) shouldBe
                TransmissionClaim.NotClaimable(NotClaimableReason.ALREADY_TRANSMITTED)
            c.file.completeTransmission(TransmissionOutcome.ContentMismatch, c.resumeState, now).claimTransmission(now, lease) shouldBe
                TransmissionClaim.NotClaimable(NotClaimableReason.QUARANTINED)
        }
        Then("restoring a state that contradicts its lease or transmission time is refused") {
            shouldThrow<AcquisitionContractViolation> {
                AcquisitionFile.restore(
                    sealed.header, sealed.cutoffAt, sealed.totals, sealed.contentSha256, sealed.contentBytes, TransmissionState.TRANSMITTING, ReceiptState.NONE,
                    ResultState.AWAITING, transmissionLeaseUntil = null,
                )
            }
            shouldThrow<AcquisitionContractViolation> {
                AcquisitionFile.restore(
                    sealed.header, sealed.cutoffAt, sealed.totals, sealed.contentSha256, sealed.contentBytes, TransmissionState.TRANSMITTED, ReceiptState.NONE,
                    ResultState.AWAITING, transmittedAt = null,
                )
            }
        }
    }
})
