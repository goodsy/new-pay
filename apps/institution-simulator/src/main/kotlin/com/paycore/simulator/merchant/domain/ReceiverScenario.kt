package com.paycore.simulator.merchant.domain

import java.time.Duration

/**
 * 가상 가맹점 수신기 응답 시나리오(WEBHOOK-001 §9 — Demo 대역, 운영 동작 아님). [reply]의 인자는 같은 이벤트의 앞선 **검증된** 전달 수(받음·중복·시나리오 거절 행)다.
 * "저장 후 연결 끊기"(응답 유실)는 payment-api 시험 대역 `ProgrammableMerchantReceiver`가 맡는다(명세 §21.7 기록 — 서블릿 응답 안에서 연결만 끊는 수단이 없다).
 */
sealed interface ReceiverScenario {
    fun reply(priorVerifiedDeliveries: Int): ReceiverReply

    data object Ok : ReceiverScenario {
        override fun reply(priorVerifiedDeliveries: Int) = ReceiverReply(OK, Duration.ZERO, 0)
    }

    /** 앞선 [failures]번은 503, 그다음부터 200. */
    data class FailThenOk(val failures: Int) : ReceiverScenario {
        init {
            require(failures >= 0) { "failures must not be negative" }
        }

        override fun reply(priorVerifiedDeliveries: Int) =
            ReceiverReply(if (priorVerifiedDeliveries < failures) UNAVAILABLE else OK, Duration.ZERO, 0)
    }

    data class Delayed(val delay: Duration) : ReceiverScenario {
        init {
            require(!delay.isNegative && !delay.isZero) { "delay must be positive" }
        }

        override fun reply(priorVerifiedDeliveries: Int) = ReceiverReply(OK, delay, 0)
    }

    /** 302(PG는 따라가지 않는다 — 실패). */
    data object Redirect : ReceiverScenario {
        override fun reply(priorVerifiedDeliveries: Int) = ReceiverReply(FOUND, Duration.ZERO, 0)
    }

    data class LargeBody(val bytes: Int) : ReceiverScenario {
        init {
            require(bytes in 1..MAX_BODY) { "body bytes must be within 1..$MAX_BODY" }
        }

        override fun reply(priorVerifiedDeliveries: Int) = ReceiverReply(OK, Duration.ZERO, bytes)
    }

    companion object {
        const val OK = 200
        const val FOUND = 302
        const val UNAVAILABLE = 503
        const val MAX_BODY = 1_048_576
    }
}
