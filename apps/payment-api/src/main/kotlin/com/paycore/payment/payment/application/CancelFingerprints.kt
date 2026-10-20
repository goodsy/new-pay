package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelPaymentCommand
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.domain.Utf8Text
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 취소 멱등 fingerprint `x1:`(CANCEL-001 §4.8 CC-06 — CP 계약 §7 `r1`·confirm `c1`과 같은 길이 접두 canonical): token(s) = `<UTF-8 길이>:s`,
 * canonical = token("paycore.cancel-payment-request") + token("1") + 필드마다(paymentId, merchantCancelId, amount, currency) token(이름) + token(값), x1 = "x1:" + lowercase hex(SHA-256).
 * 인증 범위(MID·환경)는 멱등 범위가 가진다. **v1은 동결** — 계산을 바꾸면 x2를 더한다.
 */
internal object CancelFingerprints {
    private const val DOMAIN = "paycore.cancel-payment-request"

    fun current(command: CancelPaymentCommand): RequestFingerprint = RequestFingerprint("x1:" + sha256Hex(canonicalV1(command)))

    /** 저장 값이 이 인스턴스가 계산할 수 있는 버전인가(`x1:`만). 아니면 비교하지 않는다(fail-closed — 503). */
    fun isComparable(stored: RequestFingerprint): Boolean = stored.value.startsWith("x1:")

    fun canonicalV1(command: CancelPaymentCommand): String = buildString {
        append(token(DOMAIN)).append(token("1"))
        listOf(
            "paymentId" to command.paymentId,
            "merchantCancelId" to command.merchantCancelId.value,
            "amount" to command.money.amountMinor.toString(),
            "currency" to command.money.currency.value,
        ).forEach { (name, value) -> append(token(name)).append(token(value)) }
    }

    private fun token(s: String): String = "${Utf8Text.encode(s).size}:$s"

    private fun sha256Hex(canonical: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Utf8Text.encode(canonical)))
}
