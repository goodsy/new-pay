package com.paycore.simulator.van.application

import com.paycore.simulator.van.application.required.VanIdGenerator
import com.paycore.simulator.van.domain.VanDecisionIds
import com.paycore.simulator.van.domain.VanUlid
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant

/** 기관 식별자 생성(JDK SecureRandom): 결정·예약·종결 ID = 접두 + ULID(시각 + 80bit 난수), 승인번호 8자리·기관 거래 식별자 `SIMPTX-` + 16자리(Demo 형식). */
@Component
class SecureRandomVanIdGenerator : VanIdGenerator {
    private val random = SecureRandom()

    override fun next(at: Instant): VanDecisionIds = VanDecisionIds(
        decisionId = "vd_" + ulid(at),
        pendingId = "vp_" + ulid(at),
        closureId = "vc_" + ulid(at),
        approvalNumber = digits(APPROVAL_DIGITS),
        providerTransactionId = "SIMPTX-" + digits(PTX_DIGITS),
    )

    private fun ulid(at: Instant): String = VanUlid.format(at.toEpochMilli(), random.nextInt(0x10000).toLong(), random.nextLong())

    private fun digits(n: Int): String = (1..n).joinToString("") { random.nextInt(10).toString() }

    private companion object {
        const val APPROVAL_DIGITS = 8
        const val PTX_DIGITS = 16
    }
}
