package com.paycore.admin.paymentops.application.provided

import java.time.Duration
import java.time.Instant

/**
 * 운영자 보정 취소 계약 계산(ADMIN-PAYMENT-OPS-001 §4.19 — PAYMENT 코드와 공유 없이 golden으로 맞춘 ADMIN 구현). approval은 이 Port로만 지문·실행 본문을 얻는다
 * (S4 리뷰 L-1 — paymentops domain을 직접 쓰지 않는다, Modulith named interface). 순수 계산이며 DB·외부 호출이 없다(승인 TX1 안에서 불러도 된다).
 */
interface OperatorCancelContract {
    /** `oct1:` 승인 대상 지문(golden ①). 입력 검사 실패(금액 ≤ 0, revision < 0, 모르는 목적, 빈 요소, 짝 없는 surrogate)는 `IllegalArgumentException`. */
    fun fingerprint(input: FingerprintInput): String

    /** 실행 POST 본문 바이트(golden ③ `requestExamples`와 같은 형식). */
    fun executionBody(input: ExecutionBodyInput): ByteArray
}

data class FingerprintInput(
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val balanceRevision: Long,
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val purposeKind: String,
    val purposeReference: String,
) {
    override fun toString(): String = "FingerprintInput(paymentId=$paymentId, approvalId=$approvalId)"
}

data class ExecutionBodyInput(
    val approvalId: String,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val requestedBy: String,
    val approvedBy: String,
    val reason: String,
    val purposeKind: String,
    val purposeReference: String,
    val approvedTargetFingerprint: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val correlationId: String,
) {
    override fun toString(): String = "ExecutionBodyInput(approvalId=$approvalId, paymentId=$paymentId)"
}

/**
 * 소유 서비스 실행 시간 정책(§4.13·§4.16, D-OPS-002·003 — Demo 값, 운영값 아님·LIVE Gate). 값은 paymentops 설정이 기동 때 순서를 검사한다
 * (`stale < backoff 상한 < execution-window ≤ PAYMENT max-execution-window 거울 값` — L-6).
 * - [executionWindow]: 승인 시각부터 실행 기한까지(executeBefore = approvedAt + window).
 * - [staleAfter]: 승인 TX1이 실행 행의 다음 시도 시각으로 두는 지연(connect + read timeout + 30초 — I-22). 웹 스레드가 응답 전에 죽어도 회복 job이 이어받는다.
 * - [backoff]: n번째 시도 뒤 다음 시도까지(Demo 40초 × 2^(n−1) — I-113, 상한 10분, 시도 상한 없음).
 */
interface PaymentOpsPolicy {
    val executionWindow: Duration
    val staleAfter: Duration

    fun backoff(attemptCount: Int): Duration

    fun now(): Instant

    /** 기한 확인 시작 여유(I-34 — Demo 5분): ADMIN 시각 `now ≥ executeBefore + skew`부터 기한 확인 호출. */
    val deadlineSkew: Duration

    /** 회복 job 1회 선점 상한(Demo — 운영값 LIVE Gate). */
    val recoveryBatchSize: Int

    /** backoff 상한(대사 `UNRESOLVED_WITH_DECISION` 판정 — 안정 지연 + 이 값보다 오래된 결정을 회복이 반영하지 못함). */
    val backoffMax: Duration

    /** PAYMENT 결정 목록 안정 지연의 ADMIN 쪽 거울 값(Demo 2분 — §4.17). */
    val decisionStableDelay: Duration

    /** 대사 1회 증분 페이지 상한(Demo 10). */
    val reconciliationMaxPages: Int

    /** 전체 스캔 주기(Demo 1일 — 최종 안전망, watermark를 바꾸지 않는다). */
    val reconciliationFullScanInterval: Duration
}
