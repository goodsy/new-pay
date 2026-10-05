// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badprefs.sample.domain

import java.util.prefs.Preferences

/** OS 설정 저장소(파일/레지스트리)를 domain에서 읽고 씀. */
class PreferenceOrder(val id: String) {
    fun remember(node: Preferences) = node.put("lastOrder", id)
}
