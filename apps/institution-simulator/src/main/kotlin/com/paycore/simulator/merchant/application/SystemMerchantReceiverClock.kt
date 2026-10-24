package com.paycore.simulator.merchant.application

import com.paycore.simulator.merchant.application.required.MerchantReceiverClock
import org.springframework.stereotype.Component
import java.time.Instant

/** 시스템 시계(운영). 시험은 @Primary 시험 시계로 바꾼다. */
@Component
class SystemMerchantReceiverClock : MerchantReceiverClock {
    override fun now(): Instant = Instant.now()
}
