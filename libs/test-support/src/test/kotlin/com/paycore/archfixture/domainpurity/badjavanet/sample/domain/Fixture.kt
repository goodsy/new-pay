// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badjavanet.sample.domain

import java.net.URI

class RemoteOrder(val id: String) {
    fun endpoint(): URI = URI.create("https://example.invalid/orders/" + id)
}
