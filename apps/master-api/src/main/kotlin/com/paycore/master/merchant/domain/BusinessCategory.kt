package com.paycore.master.merchant.domain

/** 업태(BUSINESS_TYPE)·종목(BUSINESS_ITEM). 순서는 **유형 안 순서**(DB `display_order`가 유형별 1..10 — 명세 §5.2 `registration_id#type#order`). */
data class BusinessCategory(val type: BusinessCategoryType, val name: CategoryName)

/** 업태/종목 구분(영속 값 `business_category.category_type`). */
enum class BusinessCategoryType { BUSINESS_TYPE, BUSINESS_ITEM }