package com.paycore.payment.payment.domain

import java.time.Instant
import java.time.LocalDate

/** 기관 결과 출처(허용 §5.2: 응답·통보·조회·대사 결과의 중복·상충 처리). */
enum class ObservationSource { RESPONSE, INQUIRY, NOTIFICATION, RECONCILIATION }

/**
 * 기관 결과 분류(허용 §5.2). [NO_EFFECT_CONFIRMED]는 기관 프로파일 Capability `confirmsNoEffect`가 있는 기관의 명시적 무효과 확정만
 * Adapter가 이 값으로 바꾼다. [NOT_FOUND]는 무효과가 아니다(시도 §2).
 */
enum class InstitutionOutcome { APPROVED, DECLINED, NO_EFFECT_CONFIRMED, PENDING, UNKNOWN, NOT_FOUND }

/**
 * 기관이 승인한 조건(승인 관찰에만). 승인번호는 전역 UNIQUE로 가정하지 않는다(허용 §5.2).
 * [institutionBusinessDate]는 기관이 알려 준 영업일(AUTH-001 검토 #1 m-2 — 기관이 주지 않으면 null). 값을 그대로 보존·비교할 뿐이며, 그 시간대·출처 의미와 정산 거래일
 * 계산에 쓰는 규칙은 미결 Q-CALENDAR가 정한다(여기서 달력 규칙을 만들지 않는다).
 */
data class ApprovedTerms(
    val money: Money,
    val plan: PaymentPlan,
    val cardType: CardType,
    val approvalNumber: String,
    val providerTransactionId: String,
    val institutionTransactionDate: LocalDate,
    val institutionBusinessDate: LocalDate?,
) {
    init {
        require(approvalNumber.isNotEmpty() && providerTransactionId.isNotEmpty()) { "approval number and provider transaction id must not be empty" }
    }
}

/** [PaymentAuthorization.recordObservation] 결과. */
enum class ObservationRecord { NEW, DUPLICATE, KEY_CONFLICT }

/** 관찰 유일 키(구현 결정 CA-15): 같은 출처의 같은 기관 결과는 한 번만 기록한다. */
data class ObservationKey(val attemptId: AttemptId, val source: ObservationSource, val sourceResultId: String)

/**
 * 기록된 관찰 한 건의 식별(AUTH-001 검토 #1 H-1): 관찰 키 + [variant]. variant 0 = 그 키의 첫 관찰, n ≥ 1 = 같은 키·다른 내용의 n번째 상충 증거
 * ([PaymentAuthorization.keyConflictingObservations] 기록 순서, 영속 `institution_observation.variant`와 같은 번호). hold를 연 관찰은 키만으로는 특정되지 않는다 —
 * 같은 키의 다른 variant는 다른 기관 내용이다.
 */
data class ObservationRef(val key: ObservationKey, val variant: Int) {
    init {
        require(variant >= 0) { "observation variant must not be negative" }
    }
}

/**
 * 기관 결과 한 건(append-only — AUTH-CONTRACT-001 §3·§6). 판정과 무관하게 기록한다. 원문 증거는 [evidenceHash] 참조만(카드번호·CVC·
 * 인증 비밀 없음). 거절·무효과·UNKNOWN·중복 수신은 금융 거래가 아니라 이 관찰로만 남는다(D-AUTH-003).
 */
data class InstitutionObservation(
    val attemptId: AttemptId,
    val source: ObservationSource,
    val sourceResultId: String,
    val outcome: InstitutionOutcome,
    val approved: ApprovedTerms?,
    val evidenceHash: String?,
    val observedAt: Instant,
) {
    init {
        require(sourceResultId.isNotEmpty()) { "sourceResultId must not be empty" }
        require((outcome == InstitutionOutcome.APPROVED) == (approved != null)) { "approved terms exist exactly for APPROVED observations" }
    }

    val key: ObservationKey
        get() = ObservationKey(attemptId, source, sourceResultId)

    /**
     * 기관이 정의한 내용이 같은가(review round2 M-2): attemptId·source·sourceResultId·outcome·approved terms만 비교한다. PG 수신 메타데이터
     * ([observedAt])와 원문 hash([evidenceHash])는 비교하지 않고 증거로만 남긴다 — 원문(전문·통보 본문)에 기관 발송 시각·전송 순번·서명 nonce
     * 같은 수신마다 달라질 수 있는 값이 없다는 보장이 없어서, hash가 다르다는 이유로 같은 결과를 상충으로 오판하지 않게 한다.
     */
    fun sameInstitutionContent(other: InstitutionObservation): Boolean =
        attemptId == other.attemptId && source == other.source && sourceResultId == other.sourceResultId &&
            outcome == other.outcome && approved == other.approved
}
