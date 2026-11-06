package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import java.time.Instant
import java.time.LocalDate

/**
 * 매입 lane((매입사, 환경)당 한 행) 저장 Port — 같은 lane의 봉인을 직렬화하고 업무일 순서를 기억한다(프로토콜 기록 = Store, CONVENTIONS §14 ③). 호출자 TX 필수.
 */
interface AcquisitionLaneStore {
    /** 행이 없으면 만든 뒤(IODKU) `FOR UPDATE`로 잠근다. 반환 = 마지막으로 봉인한 업무일(없으면 null). */
    fun lock(acquirerCode: String, environment: AcquisitionEnvironment, at: Instant): LocalDate?

    /** 잠근 행의 마지막 봉인 업무일을 [businessDate]로 바꾼다(변경 감지 + @Version). */
    fun markSealed(acquirerCode: String, environment: AcquisitionEnvironment, businessDate: LocalDate, at: Instant)

    /** (2-F 스케줄러) 잠그지 않고 마지막 봉인 업무일을 읽는다(없거나 lane이 없으면 null). 다음 봉인 날짜 추정용 — 판정은 봉인 TX의 잠금 아래에서 다시 한다. */
    fun lastSealed(acquirerCode: String, environment: AcquisitionEnvironment): LocalDate?
}
