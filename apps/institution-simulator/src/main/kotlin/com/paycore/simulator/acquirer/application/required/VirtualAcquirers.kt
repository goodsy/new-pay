package com.paycore.simulator.acquirer.application.required

import com.paycore.simulator.acquirer.domain.AcquirerScenarioBook
import java.time.Duration

/**
 * 가상 매입사 설정 Port(ACQUIRING-001 2-E — **Demo, 운영값 아님**). 설정이 없으면 매입사 0개(SFTP 서버·Worker 기동 안 함 — fail-closed). 매입사가 있으면 모든 값이
 * 필수다(없으면 기동 실패). 키 원문은 이 Port에 없다(SFTP adapter가 파일 경로에서 읽는다).
 */
interface VirtualAcquirers {
    val acquirers: List<VirtualAcquirer>

    /** 수신 → ACK 게시 기본 지연(Demo). */
    val ackDelay: Duration

    /** 수신 → 결과 파일 게시 기본 지연(Demo). */
    val resultDelay: Duration

    /** 끊긴 업로드 임시 파일(시도별 `.part`)을 지우기까지의 보존 시간(Demo — 리뷰 m-1 디스크 상한). */
    val partRetention: Duration
}

/**
 * 가상 매입사 하나. [vanClientId] = 이 매입사가 대조하는 가상 VAN 원장의 PG client(namespace) — 매입 레코드의 기관 거래 참조로 그 client의 승인·취소만 찾는다.
 */
class VirtualAcquirer(val code: String, val vanClientId: String, val scenarios: AcquirerScenarioBook) {
    override fun toString(): String = "VirtualAcquirer(code=$code, vanClientId=$vanClientId, scenarios=$scenarios)"
}
