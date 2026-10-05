// ModulithProvidedExposureSpec fixture(테스트 소스 전용, 업무 의미 없음).
package com.paycore.modulithexposure.domain.billing.application

import com.paycore.modulithexposure.domain.order.domain.Order

class BillingService {
    fun bill(order: Order): String = order.id
}
