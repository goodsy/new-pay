package com.paycore.simulator.merchant.application.required

/** 같은 (수신기, eventId)의 ACCEPTED가 동시에 기록되려 했다(MySQL 1062) — 호출자가 새 TX에서 DUPLICATE로 재판정한다. */
class DuplicateMerchantReceipt : RuntimeException("a concurrent first acceptance of the same event was recorded")
