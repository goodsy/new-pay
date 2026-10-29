package com.paycore.payment.payment.adapter.web

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 가맹점 공개 API 설정(CP-API-001 명세 §7.4·§8). **운영 기본값이 없다** — 값이 없으면 바인딩이 실패해 기동하지 않는다(MASTER-SNAPSHOT-001 §8 관례).
 * 시험 값은 test classpath `config/application.yml`의 Demo 값이다.
 *
 * @param internalPort 내부 경로(`/internal` 아래 — 권리 Snapshot Inbox 등) 전용 connector 포트. 공개 포트와 달라야 한다. 0은 시험용 임의 포트.
 * @param retryAfter 일시 불가 응답의 `Retry-After`(delta-seconds) 권고값. 재시도 권고일 뿐 Snapshot 전파·lease 시간을 약속하지 않는다.
 * @param internalAddress 내부 connector가 바인딩할 주소(선택, review round1 n-4). 없으면 모든 주소. 운영에서는 내부망 주소로 좁힐 수 있다.
 */
@ConfigurationProperties(prefix = "paycore.payment.api")
class PaymentApiSettings(
    val internalPort: Int,
    val retryAfter: RetryAfter,
    val internalAddress: String? = null,
    /** 내부 connector 작업 스레드 상한(ADMIN-PAYMENT-OPS-001 §4.7 F-8 — Demo 16, 관리 permit 합보다 커야 Entitlement Inbox를 굶기지 않는다). 기본값 없음. */
    val internalMaxThreads: Int,
    /** 내부 connector 연결 대기열 상한(Demo 16). 기본값 없음. */
    val internalAcceptCount: Int,
) {

    class RetryAfter(val eligibilityUnavailable: Duration, val temporarilyUnavailable: Duration, val requestInProgress: Duration) {
        init {
            listOf(eligibilityUnavailable, temporarilyUnavailable, requestInProgress).forEach {
                require(it >= MIN && it <= MAX) { "retry-after values must be within 1s..1h" }
            }
        }

        override fun toString(): String =
            "RetryAfter(eligibilityUnavailable=$eligibilityUnavailable, temporarilyUnavailable=$temporarilyUnavailable, requestInProgress=$requestInProgress)"
    }

    init {
        require(internalPort in 0..MAX_PORT) { "internal-port must be within 0..$MAX_PORT" }
        require(internalAddress == null || internalAddress.isNotBlank()) { "internal-address must not be blank when set" }
        require(internalMaxThreads in 1..MAX_THREADS && internalAcceptCount in 1..MAX_THREADS) { "internal-max-threads and internal-accept-count must be within 1..$MAX_THREADS" }
    }

    override fun toString(): String =
        "PaymentApiSettings(internalPort=$internalPort, internalAddress=$internalAddress, internalMaxThreads=$internalMaxThreads, internalAcceptCount=$internalAcceptCount, " +
            "retryAfter=$retryAfter)"

    private companion object {
        val MIN: Duration = Duration.ofSeconds(1)
        val MAX: Duration = Duration.ofHours(1)
        const val MAX_PORT = 65_535
        const val MAX_THREADS = 1_000
    }
}

/** delta-seconds(초 미만 올림). */
fun Duration.toRetryAfterSeconds(): String = ((toMillis() + 999) / 1000).toString()
