package com.paycore.admin.account.domain

/**
 * 등록 코드의 목적과 그 목적이 설정하는 요소(D-ADMIN1-011, §4.3.6 rev 0.3 m-A): ENROLL·RECOVERY는 비밀번호 + TOTP, PASSWORD_RESET은 비밀번호만
 * (기존 TOTP 유지), TOTP_REENROLL은 TOTP만(기존 비밀번호 유지). 어느 목적도 코드 외의 기존 요소를 요구하지 않는다.
 */
enum class EnrollmentPurpose(val setsPassword: Boolean, val setsTotp: Boolean) {
    ENROLL(setsPassword = true, setsTotp = true),
    PASSWORD_RESET(setsPassword = true, setsTotp = false),
    TOTP_REENROLL(setsPassword = false, setsTotp = true),
    RECOVERY(setsPassword = true, setsTotp = true),
}
