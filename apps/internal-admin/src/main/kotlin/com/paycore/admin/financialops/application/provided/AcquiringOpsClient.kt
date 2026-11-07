package com.paycore.admin.financialops.application.provided

import java.time.Duration
import java.time.Instant

/**
 * FINANCIAL 매입 조작 채널 클라이언트(ACQ-ADMIN-001 B1-A §4 — 서명 호출, **DB TX 밖에서만**: TX 안이면 `IllegalStateException`). 호출자는 approval의 지정 서비스뿐(ArchitectureSpec).
 * - [targets]: 재매입 대상 미리 보기(**조회 키** — `POST /internal/v1/admin/acquisition-files/{fileId}/reacquisition-targets`, 비권위·잠금 없음, I-15).
 * - [execute]·[decision]·[confirmDeadline]: **조작 전용 키**(D-ACQA-001 — 조회 키와 kid·바이트가 다르다, FINANCIAL은 조작 경로를 조작 키 서명만 허용) + 조작 permit.
 *   실행·기한 확인 본문은 승인 TX1이 저장한 바이트 그대로(재전송도 같은 바이트 — 멱등은 FINANCIAL approvalId 결정 기록).
 * 응답 불변식(FINANCIAL I-16): 200 = 커밋된 결정(REJECTED 포함) 또는 결정 없음, 409 `APPROVAL_CONFLICT` = 파생(같은 승인 ID 다른 내용), 400 = 형식, 그 밖 503.
 */
interface AcquiringOpsClient {
    fun targets(fileId: String, seqs: List<Int>): ReacquisitionTargetsResult

    fun execute(approvalId: String, body: ByteArray): AcquiringOpsResult

    fun decision(approvalId: String): AcquiringOpsResult

    fun confirmDeadline(approvalId: String, body: ByteArray): AcquiringOpsResult
}

/** 미리 보기 항목(FINANCIAL 계산 — I-8: 화면·승인은 이 값만 쓴다). 순번이 파일에 없으면 TARGET_NOT_FOUND(다른 값 null). 카드사 값 없음. */
data class ReacquisitionTargetView(
    val seq: Int,
    val transactionId: String?,
    val recordType: String?,
    val generation: Int?,
    val status: String?,
    val eligible: Boolean,
    val refusal: String?,
    val approvedAmountMinor: Long?,
    val cancelledAmountMinor: Long?,
    val expectedNetMinor: Long?,
)

data class ReacquisitionTargetsView(val fileId: String, val readAt: Instant, val items: List<ReacquisitionTargetView>)

sealed interface ReacquisitionTargetsResult {
    data class Found(val view: ReacquisitionTargetsView) : ReacquisitionTargetsResult

    /** FINANCIAL 404 `ACQUISITION_FILE_NOT_FOUND`. */
    data object NotFound : ReacquisitionTargetsResult

    data class Unavailable(val reason: String) : ReacquisitionTargetsResult
}

/** FINANCIAL 결정(`acquisition_ops_decision` 행). [decision] EXECUTED(새 파일 [resultFileId])·NOT_REQUIRED·REJECTED([code]). [origin] EXECUTION_COMMAND·DEADLINE_CONFIRMATION. */
data class AcquiringOpsDecision(
    val decision: String,
    val approvalId: String,
    val code: String?,
    val targetRef: String,
    val resultFileId: String?,
    val contentHash: String,
    val requestedBy: String,
    val approvedBy: String,
    val origin: String,
)

/**
 * 조작 호출 결과 분류(D-OPS-003 선례 — 확정 응답만 종료).
 * - [Decided]: 200 결정 본문. [confirmed] = 기한 확인 호출이 이번에 EXPIRED를 기록했는지.
 * - [None]: 200 결정 없음(조회: 아직 없음, 기한 확인: FINANCIAL 시각상 기한 전).
 * - [Malformed]: 400 형식 거절(결정 행 없이 끝남 — 같은 바이트면 언제나 같은 판정, 무결성 경보).
 * - [Unknown]: 409 `APPROVAL_CONFLICT`([integrityFlag]), 401([alert] — 키·시각), 404·413·415·5xx·timeout·연결 실패·해석 불가·permit 없음 — 결과 불명확.
 */
sealed interface AcquiringOpsResult {
    data class Decided(val decision: AcquiringOpsDecision, val confirmed: Boolean) : AcquiringOpsResult

    data object None : AcquiringOpsResult

    data class Malformed(val code: String) : AcquiringOpsResult

    data class Unknown(val failure: String, val integrityFlag: String?, val alert: Boolean) : AcquiringOpsResult
}

/**
 * 재매입 명령 계약 계산(FINANCIAL `ReacquisitionCommand`와 코드 공유 없이 같은 계약 — 독립 벡터로 대조). 순수 계산(승인 TX1 안에서 불러도 된다). 입력 형식이 FINANCIAL 문법에 맞지 않으면
 * `IllegalArgumentException`.
 */
interface ReacquisitionContract {
    fun command(input: ReacquisitionCommandInput): ReacquisitionCommandOutput
}

data class ReacquisitionCommandItem(val seq: Int, val expectedGeneration: Int, val expectedStatus: String)

data class ReacquisitionCommandInput(
    val approvalId: String,
    val originalFileId: String,
    val items: List<ReacquisitionCommandItem>,
    val requestedBy: String,
    val approvedBy: String,
    val reasonCode: String,
    val evidenceRef: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val correlationId: String,
) {
    override fun toString(): String = "ReacquisitionCommandInput(approvalId=$approvalId, originalFileId=$originalFileId, items=${items.size})"
}

/** [contentHash] = `acd1:` 내용 지문(ADMIN 실행 행 지문 = FINANCIAL 결정 contentHash), [body] = 실행·기한 확인 POST 바이트. */
class ReacquisitionCommandOutput(val contentHash: String, body: ByteArray) {
    private val bytes = body.copyOf()

    fun body(): ByteArray = bytes.copyOf()
}

/**
 * 매입 조작 실행 시간 정책(Demo 값 — 운영값 아님, LIVE Gate). [executionWindow] ≤ FINANCIAL `max-execution-window`(Demo PT24H — 넘으면 FINANCIAL이 APPROVAL_WINDOW_INVALID로 거절).
 * [staleAfter] = 승인 TX1이 실행 행 다음 시도로 두는 지연(connect + read timeout + 30초 — 웹 스레드가 응답 전에 죽어도 회복 job이 이어받는다).
 */
interface AcquiringOpsPolicy {
    val executionWindow: Duration
    val staleAfter: Duration
}
