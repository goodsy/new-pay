package com.paycore.master.merchant.domain

/** 사업장(REGISTERED)·실사업장(ACTUAL) 주소 — 유형별 현재 하나. 상세주소는 선택(C-31). */
data class BusinessAddress(val type: BusinessAddressType, val postalCode: PostalCode, val baseAddress: AddressLine, val detailAddress: AddressLine?) {
    /** 유형을 뺀 주소 내용이 같은가(ACTUAL = REGISTERED 거절 — 정책 §5 "다른 경우에만"). */
    fun samePlaceAs(other: BusinessAddress): Boolean =
        postalCode == other.postalCode && baseAddress == other.baseAddress && detailAddress == other.detailAddress
}

/** 주소 유형(영속 값 `business_address.address_type`). */
enum class BusinessAddressType { REGISTERED, ACTUAL }