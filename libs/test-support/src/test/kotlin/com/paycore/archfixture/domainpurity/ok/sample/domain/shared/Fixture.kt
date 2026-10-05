// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.ok.sample.domain.shared

import java.math.BigDecimal

/** 같은 모듈 domain 하위 패키지(허용). */
@JvmInline
value class Rate(val value: BigDecimal)
