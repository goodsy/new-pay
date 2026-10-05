// ModulithProvidedExposureSpec fixture(테스트 소스 전용, 업무 의미 없음).
package com.paycore.modulithexposure.required.billing.application

import com.paycore.modulithexposure.required.order.application.required.OrderStore

class BillingService(private val store: OrderStore) {
    fun bill(id: String) = store.save(id)
}
