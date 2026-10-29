package com.paycore.payment.payment.adapter.web.admin

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import java.security.MessageDigest
import java.util.Base64

/**
 * ADMIN 전용 키 공간 기동 검사(ADMIN-PAYMENT-OPS-001 §4.2, D-OPS-001, I-04): admin-channel 키 중 하나라도 MASTER 권리 채널(entitlement inbox) 키와 **바이트가 같거나 kid가 같으면**
 * 기동을 거부한다(교체 중 2키 각각). entitlement 키는 그 모듈의 설정 타입에 의존하지 않고 `Binder`로 속성만 읽는다(F-11 — 모듈 경계). 실패 메시지에 kid·키 원문을 넣지 않는다.
 */
class AdminChannelKeySeparation(settings: AdminChannelSettings, binder: Binder) {

    /** entitlement inbox 키 속성의 모양만(`key-id`, `secret`). */
    class InboxKey(val keyId: String, val secret: String)

    init {
        val inbox: List<InboxKey> = binder.bind(INBOX_KEYS, Bindable.listOf(InboxKey::class.java)).orElse(emptyList()) ?: emptyList()
        val inboxBytes = inbox.map {
            runCatching { Base64.getDecoder().decode(it.secret) }.getOrNull() ?: throw IllegalStateException("entitlement inbox signing key secret must be Base64")
        }
        val inboxIds = inbox.map { it.keyId }.toSet()
        settings.signingKeys.forEach { admin ->
            check(admin.keyId !in inboxIds) { "admin-channel signing key ids must differ from the entitlement inbox key ids (separate key spaces)" }
            check(inboxBytes.none { MessageDigest.isEqual(it, admin.bytes) }) {
                "admin-channel signing keys must differ from the entitlement inbox keys (separate key spaces)"
            }
        }
    }

    private companion object {
        const val INBOX_KEYS = "paycore.payment.entitlement.inbox.signing-keys"
    }
}
