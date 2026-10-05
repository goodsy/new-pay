// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooq.ok.generated

import org.jooq.Name
import org.jooq.impl.DSL

/** 생성 코드 역할(jOOQ codegen 출력 흉내): 생성 코드 자신은 org.jooq에 의존해도 된다. */
object OrderTable {
    val NAME: Name = DSL.name("orders")
}
