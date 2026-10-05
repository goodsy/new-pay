// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.coreisolation.ok.config

import com.paycore.archfixture.coreisolation.ok.FixtureApplication

/** 허용 목록의 app 공통 구성. 구성 쪽에서 base root를 참조하는 것은 허용된다. */
class WebConfig(val app: FixtureApplication)
