package com.paycore.financial.acquiring.domain

/**
 * 전송 시도 한 번의 결과(ACQUIRING-001 §4.1 전송 축 — 2-D). 판정 근거는 SFTP 절차(`.part` 업로드 → rename → 재접속 stat·되읽기 hash 대조)가 정한다:
 * - [Delivered]: 최종명에 봉인 바이트가 있음을 확인했거나 rename 성공 응답을 받았다(I-9 근거 — [evidence]).
 * - [NotDelivered]: rename을 보내지 않았거나 거절됐고 최종명이 없다고 확인됐다. 점유 때의 복귀 상태로 돌아간다(SEALED였으면 SEALED, 결과 모름이었으면
 *   TRANSMISSION_UNKNOWN — 조회 NOT_FOUND만으로 기관 미수신을 단정하지 않는다. 같은 ID·같은 바이트 재업로드는 기관 쪽 중복 판정으로 안전하다 — I-10).
 * - [Unknown]: rename을 보냈는데 응답도 확인도 없다(응답 유실·재접속 실패) → TRANSMISSION_UNKNOWN(다음 시도는 확인부터).
 * - [ContentMismatch]: 최종명에 다른 바이트가 있다(같은 ID 다른 내용) → 격리.
 */
sealed interface TransmissionOutcome {
    val code: String

    data class Delivered(val evidence: TransmissionEvidence) : TransmissionOutcome {
        override val code: String get() = "DELIVERED"
    }

    data class NotDelivered(val reason: TransmissionFailureReason) : TransmissionOutcome {
        override val code: String get() = "NOT_DELIVERED"
    }

    data class Unknown(val reason: TransmissionFailureReason) : TransmissionOutcome {
        override val code: String get() = "UNKNOWN"
    }

    data object ContentMismatch : TransmissionOutcome {
        override val code: String get() = "CONTENT_MISMATCH"
    }
}

/** 전송 완료 근거(I-9 — DB CHECK와 같은 집합). ACK hash 일치 근거(ACK_HASH_MATCH)는 수신 축 기록(2-F)이 남긴다. */
enum class TransmissionEvidence {
    /** rename 성공 응답 + 재접속 stat·되읽기 hash 일치. */
    RENAMED_AND_VERIFIED,

    /** rename 응답은 없지만(또는 앞 시도) 재접속에서 최종명의 바이트 hash가 봉인 hash와 같다. */
    VERIFIED_FINAL_PRESENT,

    /** rename 성공 응답을 받았고 재접속 확인은 실패했다. */
    RENAMED,
}

/** 전달 안 됨·결과 모름 사유(DB CHECK와 같은 집합 — 값·키 원문 없음). */
enum class TransmissionFailureReason {
    CONNECT_FAILED,
    HOST_KEY_REJECTED,
    AUTHENTICATION_FAILED,
    UPLOAD_FAILED,
    RENAME_REFUSED,
    RENAME_OUTCOME_UNKNOWN,
    VERIFY_FAILED,
    RENAMED_BUT_ABSENT,

    /** 남은 lease가 rename 안전 여유보다 짧아 rename을 보내지 않았다(lease 만료 뒤 다른 시도가 같은 임시명을 쓸 수 있다 — 리뷰 MJ-1). */
    LEASE_EXHAUSTED,

    /** 채널 밖 예외(설정·프로그래밍 오류). 접속 전이면 NOT_DELIVERED, 접속 뒤면 UNKNOWN(리뷰 m-1). */
    INTERNAL_ERROR,
}

/** 점유 결과. [Claimed.resumeState] = 이 시도가 아무 진전 없이 끝났을 때 돌아갈 상태. */
sealed interface TransmissionClaim {
    data class Claimed(val file: AcquisitionFile, val resumeState: TransmissionState) : TransmissionClaim

    data class NotClaimable(val reason: NotClaimableReason) : TransmissionClaim
}

enum class NotClaimableReason { IN_FLIGHT, ALREADY_TRANSMITTED, QUARANTINED }
