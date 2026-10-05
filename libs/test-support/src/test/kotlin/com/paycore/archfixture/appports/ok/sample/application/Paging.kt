// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.ok.sample.application

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

/** Spring Data paging 값 타입(org.springframework.data.domain)은 application에서 허용된다. */
class ListOrders {
    fun firstPage(): Pageable = PageRequest.of(0, PAGE_SIZE)

    private companion object {
        const val PAGE_SIZE = 20
    }
}
