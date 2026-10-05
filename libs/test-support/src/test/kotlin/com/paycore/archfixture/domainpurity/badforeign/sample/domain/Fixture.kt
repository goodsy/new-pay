// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.badforeign.sample.domain

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/** Foreign Function & Memory API(native 메모리·함수 호출)를 domain에서 사용. */
class OffHeapOrder(val id: String) {
    fun buffer(arena: Arena): MemorySegment = arena.allocate(id.length.toLong())
}
