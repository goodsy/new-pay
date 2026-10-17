package com.paycore.simulator.authentication.domain

import java.time.Duration
import java.time.Instant

enum class TransactionStatus { REGISTERED, AUTHENTICATED, FAILED, CANCELLED, EXPIRED }

/** 조회 응답 outcome(계약 v1). */
enum class ProviderOutcomeCode { AUTHENTICATED, FAILED, CANCELLED, EXPIRED }

/** 결과 조회가 echo하는 바인딩. */
data class BindingEcho(
    val paymentId: String,
    val merchantRef: String,
    val environment: String,
    val amountMinor: Long,
    val currency: String,
    val plan: PlanEcho,
    val cardCompany: String,
)

/**
 * 서버 간 조회 결과(계약 v1 §13.4, m-E): AUTHENTICATED만 증거 6필드(cardType·issuer·acquirer·provider·evidenceRef·authenticatedAt)를 갖고 FAILED·CANCELLED·
 * EXPIRED는 하나도 갖지 않는다. [validUntil]은 항상 있다.
 */
data class LookupResult(
    val resultRef: ResultRef,
    val transactionId: TransactionId,
    val outcome: ProviderOutcomeCode,
    val state: String,
    val binding: BindingEcho,
    val cardType: String?,
    val issuerCode: String?,
    val acquirerCode: String?,
    val providerCode: String?,
    val evidenceRef: String?,
    val authenticatedAt: Instant?,
    val validUntil: Instant,
) {
    init {
        val evidence = listOf(cardType, issuerCode, acquirerCode, providerCode, evidenceRef, authenticatedAt)
        require(if (outcome == ProviderOutcomeCode.AUTHENTICATED) evidence.all { it != null } else evidence.all { it == null }) {
            "evidence fields exist exactly for AUTHENTICATED results"
        }
    }
}

/**
 * 가상 인증기관 거래(Aggregate, Simulator 전용 DB — AUTHN-001 §13.2). 결과는 한 번만 확정되고 이후 바뀌지 않는다(기관 사실 — PG 재시작·rollback과 무관하게
 * 존속). 조회는 읽기 전용이며 outcome만 시각 함수다(AUTHENTICATED라도 validUntil 경과 뒤 EXPIRED). 시나리오는 가상 카드 참조로만 정해진다.
 */
class AuthenticationTransaction private constructor(
    val id: TransactionId,
    val clientId: String,
    val request: RegistrationRequest,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    var status: TransactionStatus = TransactionStatus.REGISTERED
        private set

    var card: VirtualCard? = null
        private set

    var resultRef: ResultRef? = null
        private set

    var evidenceRef: String? = null
        private set

    var completedAt: Instant? = null
        private set

    var validUntil: Instant? = null
        private set

    /** (client, state) 멱등: 같은 state로 같은 내용이면 같은 거래, 다르면 충돌. */
    fun sameRequest(other: RegistrationRequest): Boolean = request == other

    /** 고객이 가상 카드로 인증을 마쳤다. 거래 TTL이 지났으면 EXPIRED로 확정한다. */
    fun complete(card: VirtualCard, resultRef: ResultRef, evidenceRef: String, now: Instant, resultValidity: Duration) {
        requireOpen()
        require(evidenceRef.isNotEmpty() && evidenceRef.length <= 128) { "evidence reference must be 1..128 characters" }
        this.card = card
        this.resultRef = resultRef
        this.completedAt = now
        when {
            !now.isBefore(expiresAt) -> {
                status = TransactionStatus.EXPIRED
                validUntil = now
            }
            card.scenario == CardScenario.FAIL -> {
                status = TransactionStatus.FAILED
                validUntil = now.plus(resultValidity)
            }
            else -> {
                status = TransactionStatus.AUTHENTICATED
                this.evidenceRef = evidenceRef
                validUntil = if (card.scenario == CardScenario.EXPIRED_RESULT) now else now.plus(resultValidity)
            }
        }
    }

    /** 고객이 취소했다(거래 TTL이 지났으면 EXPIRED). */
    fun cancel(resultRef: ResultRef, now: Instant) {
        requireOpen()
        this.resultRef = resultRef
        this.completedAt = now
        this.validUntil = now
        status = if (now.isBefore(expiresAt)) TransactionStatus.CANCELLED else TransactionStatus.EXPIRED
    }

    /** 서버 간 결과 조회(읽기 전용). 아직 결과가 없으면 [RejectionReason.NOT_FOUND]. echo 변조 카드는 바인딩·state를 바꿔 돌려준다(PG가 거절해야 하는 시나리오). */
    fun lookup(now: Instant): LookupResult {
        val ref = resultRef ?: throw SimulatorRejection(RejectionReason.NOT_FOUND)
        val until = checkNotNull(validUntil)
        val c = card
        val outcome = when (status) {
            TransactionStatus.AUTHENTICATED -> if (now.isBefore(until)) ProviderOutcomeCode.AUTHENTICATED else ProviderOutcomeCode.EXPIRED
            TransactionStatus.FAILED -> ProviderOutcomeCode.FAILED
            TransactionStatus.CANCELLED -> ProviderOutcomeCode.CANCELLED
            TransactionStatus.EXPIRED -> ProviderOutcomeCode.EXPIRED
            TransactionStatus.REGISTERED -> error("a registered transaction has no result reference")
        }
        val evidenceCard = if (outcome == ProviderOutcomeCode.AUTHENTICATED) c else null
        return LookupResult(
            resultRef = ref,
            transactionId = id,
            outcome = outcome,
            state = if (c?.scenario == CardScenario.STATE_MISMATCH) request.state.reversed() else request.state,
            binding = echo(c),
            cardType = evidenceCard?.cardType,
            issuerCode = evidenceCard?.issuerCode,
            acquirerCode = evidenceCard?.acquirerCode,
            providerCode = evidenceCard?.providerCode,
            evidenceRef = evidenceCard?.let { evidenceRef },
            authenticatedAt = evidenceCard?.let { completedAt },
            validUntil = until,
        )
    }

    private fun echo(c: VirtualCard?) = BindingEcho(
        paymentId = request.paymentId,
        merchantRef = if (c?.scenario == CardScenario.WRONG_MID) request.merchantRef + "-X" else request.merchantRef,
        environment = request.environment,
        amountMinor = if (c?.scenario == CardScenario.WRONG_AMOUNT) request.amountMinor + 1 else request.amountMinor,
        currency = request.currency,
        plan = request.plan,
        cardCompany = request.cardCompany,
    )

    private fun requireOpen() {
        if (status != TransactionStatus.REGISTERED) throw SimulatorRejection(RejectionReason.ALREADY_COMPLETED)
    }

    override fun toString(): String = "AuthenticationTransaction(id=${id.value}, status=$status)"

    companion object {
        /** 새 등록. TEST 환경만 받는다(PG 쪽 격리의 이중 방어 — §13.3). 반환 URL 허용 판정은 client 설정을 아는 application이 한다. */
        fun register(id: TransactionId, clientId: String, request: RegistrationRequest, now: Instant, ttl: Duration): AuthenticationTransaction {
            if (request.environment != "TEST") throw SimulatorRejection(RejectionReason.ENVIRONMENT_NOT_SUPPORTED)
            require(!ttl.isNegative && !ttl.isZero) { "transaction ttl must be positive" }
            require(clientId.isNotEmpty() && clientId.length <= 64) { "client id must be 1..64 characters" }
            return AuthenticationTransaction(id, clientId, request, now, now.plus(ttl))
        }

        /** 영속 복원(Mapper 전용). 존재할 수 없는 조합은 거부한다. */
        @Suppress("LongParameterList")
        fun reconstitute(
            id: TransactionId, clientId: String, request: RegistrationRequest, createdAt: Instant, expiresAt: Instant, status: TransactionStatus,
            card: VirtualCard?, resultRef: ResultRef?, evidenceRef: String?, completedAt: Instant?, validUntil: Instant?,
        ): AuthenticationTransaction {
            val completed = status != TransactionStatus.REGISTERED
            require(completed == (resultRef != null) && completed == (completedAt != null) && completed == (validUntil != null)) {
                "a completed transaction has its result reference, completion time and validity"
            }
            require((status == TransactionStatus.AUTHENTICATED) == (evidenceRef != null)) { "evidence exists exactly for AUTHENTICATED transactions" }
            require(status !in setOf(TransactionStatus.AUTHENTICATED, TransactionStatus.FAILED) || card != null) { "a completed card scenario has its card" }
            return AuthenticationTransaction(id, clientId, request, createdAt, expiresAt).also {
                it.status = status
                it.card = card
                it.resultRef = resultRef
                it.evidenceRef = evidenceRef
                it.completedAt = completedAt
                it.validUntil = validUntil
            }
        }
    }
}
