package com.paycore.admin.account.application.required

import java.time.Duration

/** 복구 회차 설정(`paycore.admin.recovery` — D-SV **Demo 값, 운영값 아님**: 회차 유효 24시간). 기본값 없음. */
interface RecoverySettings {
    val episodeValidity: Duration
}
