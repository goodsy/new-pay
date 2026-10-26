package com.paycore.admin.audit.application.required

import java.time.Instant

/**
 * 감사 시각용 UTC 서버 시계(주입 — 시험에서 고정 가능, S3 리뷰 nit). account의 `AdminClock`과 같은 역할이지만 Modulith 경계상 다른 모듈의 required Port를
 * 쓸 수 없어 audit 모듈에 따로 둔다(구현은 둘 다 시스템 UTC 시계).
 */
interface AuditClock {
    fun now(): Instant
}
