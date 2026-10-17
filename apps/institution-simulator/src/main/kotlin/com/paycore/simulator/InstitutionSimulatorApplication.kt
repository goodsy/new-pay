package com.paycore.simulator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 가상 인증기관 Simulator 실행 단위(AUTHN-001 §13, D-AUTHN-004). PayCore와 별도 프로세스·포트·저장소(Simulator 전용 MySQL)이며 TEST 전용 외부 기관
 * 대역이다 — 카드번호 없이 가상 카드 참조만 다룬다. REAL 기관 연동은 없다.
 */
@SpringBootApplication
class InstitutionSimulatorApplication

fun main(args: Array<String>) {
    runApplication<InstitutionSimulatorApplication>(*args)
}
