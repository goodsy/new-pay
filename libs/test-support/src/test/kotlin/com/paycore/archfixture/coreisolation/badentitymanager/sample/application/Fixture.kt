// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.coreisolation.badentitymanager.sample.application

import com.paycore.archfixture.coreisolation.badentitymanager.TxHelper

class SneakyPersistService(private val helper: TxHelper) {
    fun place(id: String) = helper.save(id)
}
