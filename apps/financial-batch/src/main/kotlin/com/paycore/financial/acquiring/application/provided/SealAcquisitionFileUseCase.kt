package com.paycore.financial.acquiring.application.provided

import java.time.LocalDate

/**
 * 매입 파일 봉인(ACQUIRING-001 §4.2 — 배치1 범위: 후보 선정·상계·membership·봉인까지, SFTP 전송은 배치2). FINANCIAL 로컬 TX 하나(호출자 TX와 별도 — REQUIRES_NEW,
 * READ COMMITTED)에서 lane 잠금 → 후보 → 결제별 직렬화 행 잠금 → 판정 → 파일·바이트·레코드·상계·제외·lane 기록을 함께 커밋한다(전부 아니면 전무).
 * 이 TX의 커밋이 **membership 확정 시점**이다(D-ACQ-012 — 그 전 취소만 상계).
 *
 * 같은 (매입사, 환경, 업무일)을 다시 부르면 저장된 바이트를 다시 렌더링해 대조하고 [SealAcquisitionFileResult.AlreadySealed]를 돌려준다(재시작 멱등 — 새 기록 없음).
 * 대조가 어긋나면 [AcquisitionSealRefused](INTEGRITY_VIOLATION — 전송 금지, 경보).
 */
interface SealAcquisitionFileUseCase {
    fun seal(acquirerCode: String, environment: String, businessDate: LocalDate): SealAcquisitionFileResult

    /**
     * (2-F 스케줄러) 다음에 봉인할 업무일 = lane 마지막 봉인일 + 1(봉인 이력이 없으면 [firstBusinessDate]). 결과는 마감이 지났으면 [NextSealDate.Due], 아직이면
     * [NextSealDate.NotDue], 그 날에 마감 정책이 없으면 [NextSealDate.NoCutoffPolicy](리뷰 m-5 — "마감 전"과 구분해 조용히 멈추지 않게), LIVE·형식 밖 환경이면
     * [NextSealDate.Blocked]. 잠그지 않고 읽는 추정값이다 — 실제 판정(순서·마감·AlreadySealed)은 [seal]이 lane 잠금 아래에서 다시 한다.
     */
    fun nextBusinessDateToSeal(acquirerCode: String, environment: String, firstBusinessDate: LocalDate): NextSealDate
}

sealed interface NextSealDate {
    data class Due(val businessDate: LocalDate) : NextSealDate

    data class NotDue(val businessDate: LocalDate) : NextSealDate

    data class NoCutoffPolicy(val businessDate: LocalDate) : NextSealDate

    data object Blocked : NextSealDate
}

sealed interface SealAcquisitionFileResult {
    val fileId: String
    val contentSha256: String

    /**
     * 이번 호출이 봉인했다. [waiting] = 기록하지 않고 다음 봉인으로 넘긴 SALE 후보 수(사유별 — 경보·지표용). (2-F — D-ACQ-009) [cancelRecords] = 이 파일의 CANCEL
     * 레코드 수, [cancelNotRequired] = "취소 매입 불요" 기록 수, [cancelReconcile] = 원 SALE이 UNKNOWN·CONFLICT라 대사로 넘긴 취소 수(보내지 않음 — 경보),
     * [cancelWaiting] = 대기한 전송 후 취소 수(사유별).
     */
    data class Sealed(
        override val fileId: String,
        override val contentSha256: String,
        val recordCount: Int,
        val saleAmountMinor: Long,
        val nettedCancellations: Int,
        val excluded: Int,
        val waiting: Map<String, Int>,
        val cancelRecords: Int = 0,
        val cancelNotRequired: Int = 0,
        val cancelReconcile: Int = 0,
        val cancelWaiting: Map<String, Int> = emptyMap(),
    ) : SealAcquisitionFileResult

    /** 이미 봉인돼 있었다(저장 바이트 대조 통과). */
    data class AlreadySealed(override val fileId: String, override val contentSha256: String) : SealAcquisitionFileResult
}

/** 봉인을 거부했다(아무것도 기록하지 않음). 메시지에 식별자 원문 외 값(금액·키)을 넣지 않는다. */
class AcquisitionSealRefused(val reason: Reason, message: String) : RuntimeException(message) {
    enum class Reason {
        /** D-ACQ-008: 기관 계약번호 전달(PaymentAuthorized v2) 전 LIVE 매입 차단. */
        LIVE_BLOCKED,

        /** 업무일 순서 위반(I-3 — 앞 업무일 파일이 봉인되기 전, 또는 이미 지난 업무일). */
        OUT_OF_ORDER,

        /** 업무일 마감 시각이 아직 지나지 않았다(마감 전 봉인 금지 — 마감까지의 승인을 모두 담기 위해). */
        BEFORE_CUTOFF,

        /** 그 업무일에 적용할 마감 정책 버전이 없다(D-ACQ-003 — fail-closed). */
        NO_CUTOFF_POLICY,

        /** 매입사 코드·환경 값이 계약 형식이 아니다. */
        INVALID_LANE,

        /** 저장된 봉인 바이트가 저장된 헤더·레코드와 맞지 않는다(I-11). */
        INTEGRITY_VIOLATION,

        /**
         * 이미 봉인된 업무일의 (마감 정책 버전, 마감 시각)이 지금 설정과 다르다(리뷰 m-3 — D-ACQ-003 버전 관리: 시행된 버전을 소급해 바꾸거나 지우면 과거 파일의
         * 근거가 사라진다. 새 값은 새 버전·새 시행일로 더해야 한다).
         */
        CUTOFF_POLICY_CHANGED,
    }
}
