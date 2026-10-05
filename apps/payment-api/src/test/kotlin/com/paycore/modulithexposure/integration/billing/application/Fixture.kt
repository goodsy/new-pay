// ModulithProvidedExposureSpec fixture(테스트 소스 전용, 업무 의미 없음).
package com.paycore.modulithexposure.integration.billing.application

import com.paycore.modulithexposure.integration.order.adapter.integration.VanClient

class BillingService {
    fun bill(client: VanClient): String = client.endpointName
}
