package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CreatePaymentCommand
import com.paycore.payment.payment.application.provided.FailureKind
import com.paycore.payment.payment.application.provided.FailureKindSource
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.domain.Utf8Text
import java.security.MessageDigest
import java.util.HexFormat

/**
 * CreatePayment 멱등 요청 fingerprint(CP-CONTRACT-001 §7, CP-API-001 명세 §6). 범위 = 요청 본문의 닫힌 필드 7개(값 그대로, 정규화 없음).
 *
 * ```
 * token(s)           = <UTF-8 byte length of s> ":" s                 (길이·bytes 모두 엄격 UTF-8 — Utf8Text.encode, 치환 금지)
 * canonicalRequestV1 = token("paycore.create-payment-request") + token("1")
 *                    + 필드마다(merchantAccountId, merchantSiteId, merchantOrderId, amount, currency, paymentMethod, orderName)
 *                      token(name) + (값 있음: "1" + token(value) | 없음: "0")
 * r1                 = "r1:" + lowercase hex(SHA-256(UTF-8(canonicalRequestV1)))
 * ```
 *
 * **v1은 동결**한다. 계산을 바꾸면 v2를 추가하고 v1을 지우지 않는다. 비교는 저장된 기록의 버전으로 다시 계산해서 한다([compute]의
 * version, 계약 §6.4) — 지원하지 않는 버전은 [UnsupportedFingerprintVersionException](비교하지 않음, fail-closed).
 * 본문의 merchantAccountId는 인증 MID와 같음이 확인된 값이라 command의 OrderKey MID를 쓴다.
 */
internal object RequestFingerprints {
    const val CURRENT_VERSION = 1

    private const val DOMAIN = "paycore.create-payment-request"

    fun current(command: CreatePaymentCommand): RequestFingerprint = compute(command, CURRENT_VERSION)

    fun compute(command: CreatePaymentCommand, version: Int): RequestFingerprint = when (version) {
        1 -> RequestFingerprint("r1:" + sha256Hex(canonicalV1(fieldsOf(command))))
        else -> throw UnsupportedFingerprintVersionException("request")
    }

    /** 저장 값의 버전 번호(`r<n>:` 접두). */
    fun versionOf(stored: RequestFingerprint): Int = versionOfStoredValue(stored.value)

    /**
     * 저장 문자열의 버전 번호. [RequestFingerprint] 형식이 1..999로 막지만 방어적으로 해석한다(review round2 n-a): 숫자가 아니거나 Int 범위를
     * 넘으면 결함(500)이 아니라 [UnsupportedFingerprintVersionException](비교하지 않음, 503)이다.
     */
    fun versionOfStoredValue(value: String): Int {
        val colon = value.indexOf(':')
        if (!value.startsWith('r') || colon < 2) throw UnsupportedFingerprintVersionException("request")
        return value.substring(1, colon).toIntOrNull() ?: throw UnsupportedFingerprintVersionException("request")
    }

    /** 계약 §7 필드 순서의 (이름, 값) 목록. golden vector 시험이 같은 함수를 쓴다. */
    fun fieldsOf(command: CreatePaymentCommand): List<Pair<String, String?>> = listOf(
        "merchantAccountId" to command.orderKey.merchantAccountId.value,
        "merchantSiteId" to command.orderIntent.merchantSiteId.value,
        "merchantOrderId" to command.orderKey.merchantOrderId.value,
        "amount" to command.orderIntent.money.amountMinor.toString(),
        "currency" to command.orderIntent.money.currency.value,
        "paymentMethod" to command.paymentMethod.name,
        "orderName" to command.orderName,
    )

    fun canonicalV1(fields: List<Pair<String, String?>>): String = buildString {
        append(token(DOMAIN)).append(token("1"))
        fields.forEach { (name, value) ->
            append(token(name))
            if (value == null) append('0') else append('1').append(token(value))
        }
    }

    private fun token(s: String): String = "${Utf8Text.encode(s).size}:$s"

    private fun sha256Hex(canonical: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Utf8Text.encode(canonical)))
}

/**
 * 저장된 fingerprint의 버전을 이 인스턴스가 계산할 수 없다(새 버전 배포 뒤 롤백된 인스턴스 등). 서로 다른 버전 값을 직접 비교해
 * IDEMPOTENCY_KEY_REUSED·ORDER_INTENT_CONFLICT로 오분류하지 않고, TX를 rollback해 일시 불가(503)로 돌린다(CP-API-001 명세 §6.4).
 * 메시지에 값을 넣지 않는다.
 */
internal class UnsupportedFingerprintVersionException(kind: String) :
    RuntimeException("unsupported stored $kind fingerprint version", null, false, false),
    FailureKindSource {
    override val failureKind: FailureKind = FailureKind.UNSUPPORTED_FINGERPRINT_VERSION
}
