// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.ok.sample.application

import com.paycore.archfixture.appports.ok.billing.application.provided.QuoteFee
import com.paycore.archfixture.appports.ok.sample.application.required.OrderStore
import com.paycore.archfixture.appports.ok.sample.domain.Order
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * application 허용 목록 준수 예: @Service, @Transactional, ApplicationEventPublisher, @Value, Pageable, slf4j Logger,
 * 자기 모듈 domain·required Port, 다른 모듈의 application.provided(모듈 간 공개 범위는 Modulith verify가 검사).
 */
@Service
class ConfirmOrderService(
    private val store: OrderStore,
    private val fees: QuoteFee,
    private val events: ApplicationEventPublisher,
    @Value("\${fixture.max-page-size:50}") private val maxPageSize: Int,
) {
    @Transactional
    fun confirm(id: String, page: Pageable): Order {
        val order = Order(id)
        store.save(order)
        log.info("confirmed {} fee={} pageSize={}", id, fees.quote(id), minOf(page.pageSize, maxPageSize))
        events.publishEvent(OrderConfirmed(id))
        return order
    }

    private companion object {
        val log = LoggerFactory.getLogger(ConfirmOrderService::class.java)
    }
}

data class OrderConfirmed(val id: String)
