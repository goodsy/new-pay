package com.paycore.admin.audit.application.provided

/**
 * 업무 TX가 없는 보안 사건의 감사(자기 로컬 TX — §4.6 "인증 경로 IP 거부"). 행위자는 ANONYMOUS, 원격 주소와 고정 요약만(요청 내용 없음).
 */
interface AdminSecurityEventRecorder {
    /** 미인증 경로의 IP 허용 목록 거부 — [area]는 고정 값 `AUTH`(`/api/auth/`)·`ENROLL`(`/api/enroll/` — S7 리뷰 m-5). */
    fun recordIpDenied(remoteIp: String, correlationId: String, area: String)
}
