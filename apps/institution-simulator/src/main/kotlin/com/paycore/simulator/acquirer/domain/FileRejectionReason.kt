package com.paycore.simulator.acquirer.domain

/**
 * 파일 전체 거절 사유(ACK FILE_REJECTED·RECEIPT_CONFLICT의 reasonCode — 가상 코드, `[A-Z0-9_]{1,32}`). 받는 쪽(FINANCIAL)은 업무 의미를 해석하지 않고 기록·노출만 한다.
 */
enum class FileRejectionReason {
    /** 인코딩(US-ASCII·LF·BOM/CR 없음·빈 줄 없음)·줄 종류·필드 수·필드 문법·순번 위반. */
    FORMAT_INVALID,

    /** 레코드 모양(SALE 순액 = 승인액 − 상계, CANCEL 원거래 참조·상계 없음, 할부 개월, generation·재매입 참조) 위반. */
    RECORD_INVALID,

    /** 트레일러 bodySha256 ≠ H·D 줄 바이트의 SHA-256. */
    BODY_HASH_MISMATCH,

    /** 트레일러 건수·합계·순액 ≠ 레코드에서 계산한 값. */
    TOTALS_MISMATCH,

    /** 업로드 최종명의 파일 ID ≠ 헤더의 파일 ID. */
    FILE_NAME_MISMATCH,

    /** 헤더 매입사 ≠ 업로드한 계정의 매입사(chroot). */
    ACQUIRER_MISMATCH,

    /** TEST 외 환경(가상 매입사는 TEST만 받는다 — LIVE 매입은 D-ACQ-008로 차단). */
    ENVIRONMENT_NOT_SUPPORTED,

    /** 파일 크기 상한(16 MiB − 1 — FINANCIAL 봉인 바이트 상한과 같다) 초과. */
    FILE_TOO_LARGE,

    /** 결정적 시나리오 FILE_REJECT(Demo — 파일 전체 거절 재현). */
    SCENARIO_FILE_REJECTED,

    /** 같은 파일 ID에 앞서 받은 것과 다른 내용(RECEIPT_CONFLICT의 사유). */
    CONTENT_MISMATCH,
}
