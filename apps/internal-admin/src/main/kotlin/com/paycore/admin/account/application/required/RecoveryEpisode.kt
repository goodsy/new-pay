package com.paycore.admin.account.application.required

import java.time.Instant

/** 복구 회차 행(§4.9.2 `admin_recovery_episode`). 열린 회차 = [closedAt]이 null(생성 열 `open_marker` UNIQUE로 최대 1개). */
data class RecoveryEpisode(val episodeNo: Long, val openedAt: Instant, val expiresAt: Instant, val closedAt: Instant?) {
    fun isOpenAt(now: Instant): Boolean = closedAt == null && expiresAt.isAfter(now)
}
