package com.paycore.financial.acquiring.adapter.web.admin

import org.springframework.boot.context.properties.bind.Binder
import java.security.MessageDigest
import java.util.Base64

/**
 * 채널별 별도 키 기동 검사(ACQ-VIEW-001 §3.1): admin-channel 키 중 하나라도 결제 사실 Inbox(PAYMENT → FINANCIAL) 서명 키와 **바이트가 같거나 kid가 같으면** 기동을 거부한다
 * (교체 중 2키 각각 — 양쪽 모두). Inbox 키는 paymentfact 모듈의 설정 타입에 의존하지 않고 `Binder`로 속성만 읽는다(모듈 경계). 실패 메시지에 kid·키 원문을 넣지 않는다.
 */
class AdminChannelKeySeparation(settings: AdminChannelSettings, binder: Binder) {

    init {
        val inbox = (0 until MAX_INBOX_KEYS).mapNotNull { i ->
            val kid = binder.bind("$INBOX_KEYS[$i].key-id", String::class.java).orElse(null)
            val secret = binder.bind("$INBOX_KEYS[$i].secret", String::class.java).orElse(null)
            if (kid == null && secret == null) {
                null
            } else {
                kid to secret?.let {
                    runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: throw IllegalStateException("payment-fact inbox signing key must be Base64")
                }
            }
        }
        settings.signingKeys.forEach { admin ->
            inbox.forEach { (kid, bytes) ->
                check(admin.keyId != kid) { "admin-channel signing key ids must differ from the payment-fact inbox key ids (separate key spaces)" }
                check(bytes == null || !MessageDigest.isEqual(bytes, admin.bytes)) {
                    "admin-channel signing keys must differ from the payment-fact inbox keys (separate key spaces)"
                }
            }
        }
    }

    private companion object {
        const val INBOX_KEYS = "paycore.financial.payment-fact-inbox.signing-keys"
        const val MAX_INBOX_KEYS = 8
    }
}
