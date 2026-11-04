package com.paycore.master.merchant.adapter.web.admin

import org.springframework.boot.context.properties.bind.Binder
import java.security.MessageDigest
import java.util.Base64

/**
 * ADMIN 전용 키 공간 기동 검사(MERCHANT-OPS-001 §3 R-2): admin-channel 키 중 하나라도 권리 채널(MASTER → PAYMENT dispatch) 서명 키와 **바이트가 같거나 kid가 같으면** 기동을
 * 거부한다(교체 중 2키 각각). dispatch 키는 integration adapter 설정 타입에 의존하지 않고 `Binder`로 속성만 읽는다(web adapter 규칙). 실패 메시지에 kid·키 원문을 넣지 않는다.
 */
class AdminChannelKeySeparation(settings: AdminChannelSettings, binder: Binder) {

    init {
        val dispatchKeyId = binder.bind(DISPATCH_KEY_ID, String::class.java).orElse(null)
        val dispatchBytes = binder.bind(DISPATCH_KEY, String::class.java).orElse(null)?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: throw IllegalStateException("entitlement dispatch signing key must be Base64")
        }
        settings.signingKeys.forEach { admin ->
            check(admin.keyId != dispatchKeyId) { "admin-channel signing key ids must differ from the entitlement dispatch key id (separate key spaces)" }
            check(dispatchBytes == null || !MessageDigest.isEqual(dispatchBytes, admin.bytes)) {
                "admin-channel signing keys must differ from the entitlement dispatch key (separate key spaces)"
            }
        }
    }

    private companion object {
        const val DISPATCH_KEY_ID = "paycore.master.entitlement.dispatch.signing-key-id"
        const val DISPATCH_KEY = "paycore.master.entitlement.dispatch.signing-key"
    }
}
