// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.bootapp.badtwo

import org.springframework.boot.autoconfigure.SpringBootApplication

/** base root에 두 번째 @SpringBootApplication: base root 규칙(각자 entry point 형태)만으로는 통과한다. */
@SpringBootApplication
class SecondApplication
