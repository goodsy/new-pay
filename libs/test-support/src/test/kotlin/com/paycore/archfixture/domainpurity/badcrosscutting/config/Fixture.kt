// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badcrosscutting.config

/** 앱 공통 구성(crossCutting) 자리. */
class AuditHelper {
    fun mark(id: String): String = "audited:" + id
}
