// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.ok.sample.application

import org.springframework.transaction.annotation.Transactional

/** TX 경계(@Transactional)는 영속 기술이 아니다(org.springframework.transaction). */
class OrderService {
    @Transactional
    fun place(id: String): String = id
}
