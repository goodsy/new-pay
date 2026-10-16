package com.paycore.payment.payment.application.provided

/**
 * 저장소 실패의 진단 값(n-3): MySQL vendor 오류 코드·SQLState·제약 업무 이름만. 값·메시지 원문은 담지 않는다. persistence adapter가 채우고
 * web 오류 처리기가 구조화 로그에 남긴다(web은 `java.sql`을 볼 수 없다 — ArchUnit 영속 기술 위치 규칙).
 */
data class StoreDiagnostic(val vendorCode: Int?, val sqlState: String?, val constraint: String? = null)

/** 원인 예외가 저장소 진단 값을 갖는다(persistence adapter가 채움). */
interface StoreDiagnosticSource {
    val diagnostic: StoreDiagnostic?
}
