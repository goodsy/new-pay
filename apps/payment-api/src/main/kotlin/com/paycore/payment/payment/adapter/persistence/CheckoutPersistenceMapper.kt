package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutTokenRecord
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.CheckoutTokenId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.MerchantReturnUrl
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ReturnUrlParse
import com.paycore.payment.payment.domain.SecretDigest

/** 결제창 토큰·결제창 세션 기록 ↔ 행 Mapper(AUTHN-001 §10.1). 저장된 URL은 같은 엄격 문법으로 다시 파싱한다(저장 값이 깨졌으면 결함 — 조용히 쓰지 않는다). */
internal object CheckoutPersistenceMapper {

    fun newTokenRow(t: CheckoutTokenRecord): JpaCheckoutTokenEntity = JpaCheckoutTokenEntity.newRow(
        checkoutTokenId = t.id.value, tokenDigest = t.digest.value, paymentId = t.paymentId.value,
        environment = t.orderKey.environment.name, merchantAccountId = t.orderKey.merchantAccountId.value, merchantOrderId = t.orderKey.merchantOrderId.value,
        merchantSiteId = t.merchantSiteId.value, amountMinor = t.money.amountMinor, currency = t.money.currency.value,
        successUrl = t.successUrl.value, failUrl = t.failUrl.value, siteHost = t.siteHost, siteSnapshotVersion = t.siteSnapshotVersion,
        status = t.status.name, issuedAt = t.issuedAt, expiresAt = t.expiresAt, consumedAt = t.consumedAt, closedAt = t.closedAt,
        issuedByCredentialId = t.issuedByCredentialId.value, policyVersion = t.policyVersion,
    )

    fun toToken(row: JpaCheckoutTokenEntity) = CheckoutTokenRecord(
        id = CheckoutTokenId(row.checkoutTokenId),
        digest = SecretDigest.parse(row.tokenDigest),
        paymentId = PaymentId(row.paymentId),
        orderKey = PaymentPersistenceMapper.orderKeyOf(row.environment, row.merchantAccountId, row.merchantOrderId),
        merchantSiteId = MerchantSiteId(row.merchantSiteId),
        money = Money(row.amountMinor, CurrencyCode(row.currency)),
        successUrl = storedUrl(row.successUrl),
        failUrl = storedUrl(row.failUrl),
        siteHost = row.siteHost,
        siteSnapshotVersion = row.siteSnapshotVersion,
        status = CheckoutTokenStatus.valueOf(row.status),
        issuedAt = row.issuedAt,
        expiresAt = row.expiresAt,
        consumedAt = row.consumedAt,
        closedAt = row.closedAt,
        issuedByCredentialId = CredentialKeyId(row.issuedByCredentialId),
        policyVersion = row.policyVersion,
    )

    fun applyToken(t: CheckoutTokenRecord, row: JpaCheckoutTokenEntity) {
        check(row.checkoutTokenId == t.id.value && row.tokenDigest == t.digest.value) { "checkout token row does not match the record" }
        row.status = t.status.name
        row.consumedAt = t.consumedAt
        row.closedAt = t.closedAt
    }

    fun newSessionRow(s: CheckoutSessionRecord): JpaCheckoutSessionEntity = JpaCheckoutSessionEntity.newRow(
        checkoutSessionId = s.id.value, handle = s.handle.value, sessionDigest = s.digest.value, checkoutTokenId = s.tokenId.value,
        paymentId = s.paymentId.value, successUrl = s.successUrl.value, failUrl = s.failUrl.value, siteHost = s.siteHost, status = s.status.name,
        closeReason = s.closeReason?.name, createdAt = s.createdAt, expiresAt = s.expiresAt, closedAt = s.closedAt, policyVersion = s.policyVersion,
    )

    fun toSession(row: JpaCheckoutSessionEntity) = CheckoutSessionRecord(
        id = CheckoutSessionId(row.checkoutSessionId),
        handle = CheckoutHandle(row.handle),
        digest = SecretDigest.parse(row.sessionDigest),
        tokenId = CheckoutTokenId(row.checkoutTokenId),
        paymentId = PaymentId(row.paymentId),
        successUrl = storedUrl(row.successUrl),
        failUrl = storedUrl(row.failUrl),
        siteHost = row.siteHost,
        status = CheckoutSessionStatus.valueOf(row.status),
        closeReason = row.closeReason?.let(CheckoutSessionCloseReason::valueOf),
        createdAt = row.createdAt,
        expiresAt = row.expiresAt,
        closedAt = row.closedAt,
        policyVersion = row.policyVersion,
    )

    fun applySession(s: CheckoutSessionRecord, row: JpaCheckoutSessionEntity) {
        check(row.checkoutSessionId == s.id.value && row.sessionDigest == s.digest.value) { "checkout session row does not match the record" }
        row.status = s.status.name
        row.closeReason = s.closeReason?.name
        row.closedAt = s.closedAt
    }

    private fun storedUrl(raw: String): MerchantReturnUrl =
        (MerchantReturnUrl.parse(raw) as? ReturnUrlParse.Accepted)?.url ?: error("a stored return URL violates the grammar")
}
