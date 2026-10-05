// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badslf4j.sample.domain

import org.slf4j.LoggerFactory

class LoggingOrder(val id: String) {
    fun close() = LoggerFactory.getLogger(LoggingOrder::class.java).info("closed {}", id)
}
