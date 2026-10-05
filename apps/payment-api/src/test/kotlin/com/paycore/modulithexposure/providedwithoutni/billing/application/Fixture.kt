// ModulithProvidedExposureSpec fixture(테스트 소스 전용, 업무 의미 없음).
package com.paycore.modulithexposure.providedwithoutni.billing.application

import com.paycore.modulithexposure.providedwithoutni.order.application.provided.PlaceOrder

class BillingService(private val placeOrder: PlaceOrder) {
    fun bill(id: String): String = placeOrder.place(id)
}
