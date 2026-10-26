package com.paycore.admin.account.domain

/**
 * 계정의 대기 사유와 발급 근거(§4.3.1 rev 0.3 H-B): 등록 완료 TX는 코드 행의 목적·근거가 이 값과 같을 때만 진행한다.
 * 근거 형식: ENROLL = 발급 ID(`enc_` + ULID), 재설정·재등록 = 승인 ID(`apr_` + ULID), 복구 = `episode:<회차 번호>` — 최대 40자(DB 열 길이).
 */
data class PendingEnrollment(val purpose: EnrollmentPurpose, val basis: String) {
    init {
        require(BASIS.matches(basis)) { "enrollment basis must be 1-40 characters of [A-Za-z0-9_:]" }
    }

    private companion object {
        val BASIS = Regex("^[A-Za-z0-9_:]{1,40}$")
    }
}
