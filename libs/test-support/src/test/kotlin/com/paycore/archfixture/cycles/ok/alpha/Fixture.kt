// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.cycles.ok.alpha

import com.paycore.archfixture.cycles.ok.beta.BetaService

class AlphaService(private val beta: BetaService) {
    fun run(): String = "alpha>" + beta.run()
}
