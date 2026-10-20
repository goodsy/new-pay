package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancellationId
import java.time.Instant

/** 한 번의 취소 시작에 쓸 서버 생성 식별자(CANCEL-001 CN-07): 취소 `pcn_`·Attempt `pca_`·기관 취소 요청 ID `cr_`(+ ULID — 시각 = 시작 시각, 가상 VAN이 연령을 본다). */
data class NewCancellationIdentifiers(val cancellationId: CancellationId, val attemptId: CancelAttemptId, val requestId: String)

/** 취소 식별자 생성 Port(승인 생성기 이름·계약 불변 — CN-07). 조회 ID `cq_`는 조회마다 새로 만든다. */
interface CancellationIdentifierGenerator {
    fun next(at: Instant): NewCancellationIdentifiers

    fun inquiryId(at: Instant): String
}
