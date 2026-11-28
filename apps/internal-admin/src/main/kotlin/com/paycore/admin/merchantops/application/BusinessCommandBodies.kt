package com.paycore.admin.merchantops.application

import com.paycore.admin.merchantops.application.provided.BusinessProfileContent

/** MASTER 사업자 명령 본문의 내용 부분(§6.6 — 모든 key, 선택 값은 JSON null, 목록은 요청 순서 그대로 = 지문 순서). 1인 수정(BusinessProfileService)과 중요정보 변경 제안이 같은 모양을 쓴다. */
internal object BusinessCommandBodies {
    fun content(body: MutableMap<String, Any?>, c: BusinessProfileContent) {
        body["businessType"] = c.businessType
        body["displayName"] = c.displayName
        body["registration"] = c.registration.let {
            linkedMapOf(
                "corporateRegistrationNumber" to it.corporateRegistrationNumber, "openingDate" to it.openingDate, "countryCode" to it.countryCode,
                "englishName" to it.englishName, "phoneNumber" to it.phoneNumber, "email" to it.email,
            )
        }
        body["representatives"] = c.representatives.map { linkedMapOf("name" to it.name, "representativeType" to it.representativeType) }
        body["categories"] = c.categories.map { linkedMapOf("categoryType" to it.categoryType, "name" to it.name) }
        body["addresses"] = c.addresses.map {
            linkedMapOf("addressType" to it.addressType, "postalCode" to it.postalCode, "baseAddress" to it.baseAddress, "detailAddress" to it.detailAddress)
        }
        body["contacts"] = c.contacts.map { linkedMapOf("name" to it.name, "email" to it.email, "phoneNumber" to it.phoneNumber, "primary" to it.primary) }
    }
}
