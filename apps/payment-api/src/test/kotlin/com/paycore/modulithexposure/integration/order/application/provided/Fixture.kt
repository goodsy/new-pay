// ModulithProvidedExposureSpec fixture(테스트 소스 전용, 업무 의미 없음).
package com.paycore.modulithexposure.integration.order.application.provided

interface PlaceOrder {
    fun place(id: String): String
}
