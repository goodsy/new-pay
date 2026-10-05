// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.baseroot.badfacade

/** <base> 바로 아래 파일의 최상위 함수 → HelpersKt facade 클래스(Application facade가 아님). */
fun normalize(id: String): String = id.trim()
