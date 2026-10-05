// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.cycles.bad.beta

import com.paycore.archfixture.cycles.bad.alpha.AlphaService

class BetaService {
    fun run(alpha: AlphaService): String = "beta>" + alpha.name()

    fun name(): String = "beta"
}
