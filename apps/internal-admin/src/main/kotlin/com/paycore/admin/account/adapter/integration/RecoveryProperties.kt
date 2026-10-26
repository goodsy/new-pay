package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.RecoverySettings
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 복구 회차 설정(`paycore.admin.recovery` — D-SV **Demo 값, 운영값 아님**: 회차 유효 24시간). 기본값 없음 — 없으면 기동 실패. */
@ConfigurationProperties(prefix = "paycore.admin.recovery")
class RecoveryProperties(override val episodeValidity: Duration) : RecoverySettings {
    init {
        require(!episodeValidity.isNegative && !episodeValidity.isZero) { "paycore.admin.recovery.episode-validity must be positive" }
    }
}
