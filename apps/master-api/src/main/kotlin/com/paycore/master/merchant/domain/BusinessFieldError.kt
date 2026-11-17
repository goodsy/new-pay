package com.paycore.master.merchant.domain

/**
 * 필드 오류(명세 §4.6 — 지침서 §8 "필드별 오류 반환"). [path]·[code]만 담고 **입력 값을 담지 않는다**. path 문법은 §4.6
 * (`registration.phoneNumber`, `representatives[0].name`, `categories` 등 — i는 요청 목록의 0부터 위치).
 */
data class BusinessFieldError(val path: String, val code: BusinessFieldErrorCode)

/** §4.6 code 집합(API 응답 값 — BIZ-2 골든이 고정한다). */
enum class BusinessFieldErrorCode {
    REQUIRED,
    FORMAT,
    TOO_LONG,
    TOO_MANY,
    DUPLICATE,
    NOT_ALLOWED,
    SOLE_WITH_OTHERS,
    PRIMARY_REQUIRED,
    PRIMARY_MULTIPLE,
    ACTUAL_SAME_AS_REGISTERED,
    BUSINESS_TYPE_ITEM_REQUIRED,
}