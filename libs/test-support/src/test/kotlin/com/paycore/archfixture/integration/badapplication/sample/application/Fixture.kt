// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.integration.badapplication.sample.application

import com.paycore.archfixture.integration.badapplication.sample.adapter.integration.VanAuthRequest

class DirectVanService {
    fun build(amount: Long): VanAuthRequest = VanAuthRequest(amount)
}
