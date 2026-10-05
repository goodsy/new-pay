// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.cycles.bad.alpha

import com.paycore.archfixture.cycles.bad.beta.BetaService

class AlphaService {
    fun run(beta: BetaService): String = "alpha>" + beta.name()

    fun name(): String = "alpha"
}
