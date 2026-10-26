package com.paycore.admin.account.application.required

/** 새 TOTP 시크릿(160비트 — §4.3.4). */
interface TotpSecretGenerator {
    fun newSecret(): ByteArray
}
