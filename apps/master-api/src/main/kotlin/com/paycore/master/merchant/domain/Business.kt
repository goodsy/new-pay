package com.paycore.master.merchant.domain

import java.time.Instant

/** 법적 사업 주체. 하나의 Business가 복수 Merchant를 가질 수 있다(초기). 계약 당사자(D-MD-004). */
class Business private constructor(
    val id: BusinessId,
    val registrationNumber: BusinessRegistrationNumber,
    val displayName: DisplayName,
    val lifecycle: Lifecycle,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_ACTIVE)
    }

    override fun toString(): String = "Business(id=${id.value}, registrationNumber=${registrationNumber.masked()}, lifecycle=$lifecycle)"

    companion object {
        fun register(id: BusinessId, registrationNumber: BusinessRegistrationNumber, displayName: DisplayName, at: Instant, by: ActorRef) =
            Business(id, registrationNumber, displayName, Lifecycle.ACTIVE, at, by)

        /** persistence Mapper 전용 복원. */
        fun reconstitute(
            id: BusinessId,
            registrationNumber: BusinessRegistrationNumber,
            displayName: DisplayName,
            lifecycle: Lifecycle,
            createdAt: Instant,
            createdBy: ActorRef,
        ) = Business(id, registrationNumber, displayName, lifecycle, createdAt, createdBy)
    }
}
