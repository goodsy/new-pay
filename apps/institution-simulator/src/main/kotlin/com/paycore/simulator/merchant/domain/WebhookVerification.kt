package com.paycore.simulator.merchant.domain

/** 판정 결과와 (통과면) 일치한 키 ID. */
data class WebhookVerification(val verdict: WebhookVerdict, val keyId: String?)
