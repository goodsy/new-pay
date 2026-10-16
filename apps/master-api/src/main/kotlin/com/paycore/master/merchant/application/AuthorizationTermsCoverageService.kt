package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.required.MasterClock
import org.springframework.stereotype.Service
import java.time.temporal.ChronoUnit

/** 승인 시작 조건 누락 점검(jOOQ 읽기 Port에 위임, 시각은 서버 clock). */
@Service
class AuthorizationTermsCoverageService(
    private val reader: com.paycore.master.merchant.application.required.AuthorizationTermsCoverageReader,
    private val clock: MasterClock,
) : com.paycore.master.merchant.application.provided.AuthorizationTermsCoverageQuery {
    override fun overview(): com.paycore.master.merchant.application.provided.TermsCoverageOverview =
        reader.overview(clock.now().truncatedTo(ChronoUnit.MILLIS))
}
