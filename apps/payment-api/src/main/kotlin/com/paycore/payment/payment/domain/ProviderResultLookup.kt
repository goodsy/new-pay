package com.paycore.payment.payment.domain

sealed interface ProviderResultLookup {
    data class Found(val result: ProviderAuthenticationResult) : ProviderResultLookup

    data class Unverifiable(val reason: LookupFailure) : ProviderResultLookup
}
