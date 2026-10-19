package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.ConfirmPaymentCommand
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.domain.Utf8Text
import java.security.MessageDigest
import java.util.HexFormat

/**
 * confirm 멱등 fingerprint `c1:`(AUTH-001 AU-03 — CP 계약 §7 `r1`과 같은 길이 접두 canonical): token(s) = `<UTF-8 길이>:s`,
 * canonical = token("paycore.confirm-payment-request") + token("1") + 필드마다(paymentId, authenticationRef, merchantOrderId, amount, currency) token(이름) + token(값),
 * c1 = "c1:" + lowercase hex(SHA-256). **v1은 동결** — 계산을 바꾸면 c2를 더한다.
 */
internal object ConfirmFingerprints {
    private const val DOMAIN = "paycore.confirm-payment-request"

    fun current(command: ConfirmPaymentCommand): RequestFingerprint = RequestFingerprint("c1:" + sha256Hex(canonicalV1(command)))

    /** 저장 값이 이 인스턴스가 계산할 수 있는 버전인가(`c1:`만). 아니면 비교하지 않는다(fail-closed — 503). */
    fun isComparable(stored: RequestFingerprint): Boolean = stored.value.startsWith("c1:")

    fun canonicalV1(command: ConfirmPaymentCommand): String = buildString {
        append(token(DOMAIN)).append(token("1"))
        listOf(
            "paymentId" to command.paymentId,
            "authenticationRef" to command.authenticationRef,
            "merchantOrderId" to command.merchantOrderId,
            "amount" to command.money.amountMinor.toString(),
            "currency" to command.money.currency.value,
        ).forEach { (name, value) -> append(token(name)).append(token(value)) }
    }

    private fun token(s: String): String = "${Utf8Text.encode(s).size}:$s"

    private fun sha256Hex(canonical: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Utf8Text.encode(canonical)))
}
