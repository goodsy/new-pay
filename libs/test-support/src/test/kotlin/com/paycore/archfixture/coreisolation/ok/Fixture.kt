// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.coreisolation.ok

import com.paycore.archfixture.coreisolation.ok.sample.application.provided.PlaceOrder

/** <base> 바로 아래 Application 자리. adapter·구성은 이쪽을 써도 되지만 domain/application은 쓰지 않는다. */
class FixtureApplication(val placeOrder: PlaceOrder)
