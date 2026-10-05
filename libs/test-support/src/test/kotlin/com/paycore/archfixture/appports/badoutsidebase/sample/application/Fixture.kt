// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.badoutsidebase.sample.application

import com.paycore.archfixture.appports.sharedlib.persistence.EntityManagerWrapper

class WrappedPersistService(private val wrapper: EntityManagerWrapper) {
    fun place(id: String) = wrapper.save(id)
}
