// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.ok.billing.application.provided

/** 다른 모듈(billing)의 공개 Port. sample application이 사용해도 applicationDependsOnlyOnPorts는 막지 않는다. */
fun interface QuoteFee {
    fun quote(orderId: String): Long
}
