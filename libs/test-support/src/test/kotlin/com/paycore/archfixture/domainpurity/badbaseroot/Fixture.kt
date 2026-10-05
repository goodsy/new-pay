// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badbaseroot

import org.springframework.stereotype.Component

/** <base> 바로 아래에 둔 Spring stereotype(meta-annotation @Component). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class CoreComponent
