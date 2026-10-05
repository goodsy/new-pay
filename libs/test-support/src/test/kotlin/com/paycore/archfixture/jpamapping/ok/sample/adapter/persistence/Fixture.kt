// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jpamapping.ok.sample.adapter.persistence

import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass

@MappedSuperclass
abstract class BaseJpaEntity

@Embeddable
class AmountEmbeddable(val amount: Long)

@Entity
class OrderJpaEntity(val id: String, val amount: AmountEmbeddable) : BaseJpaEntity()
