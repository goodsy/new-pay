package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.HomeKpiItem
import com.paycore.payment.payment.domain.HomeKpiWindow

/**
 * 운영 홈 KPI 집계 Port(ADMIN-HOME-KPI-001 §5 — 구현 `adapter.query.JooqHomeKpiReader`, jOOQ CQRS 조회 측, 쓰기·잠금 없음, 비권위). 한 읽기 전용 REPEATABLE READ TX의 2문장
 * (합계·건수)이 같은 스냅샷을 본다. 모든 SELECT에 질의 시간 상한 hint. 질의 시간 초과는 [QueryTimedOut], 그 밖 접근 실패는 [PersistenceFailure.Unavailable].
 * 사용처는 `HomeKpiQueryService`뿐(ArchUnit). 순서는 보장하지 않는다(정렬은 application).
 */
interface HomeKpiReader {
    fun read(window: HomeKpiWindow): List<HomeKpiItem>
}
