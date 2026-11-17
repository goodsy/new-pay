package com.paycore.master.merchant.domain

/** 사업체 구분(BUSINESS-PROFILE-001 §4.1, TBD-10 — 국외·비영리 확장 없음). 영속 값(`business.business_type`)이라 이름을 바꾸지 않는다(추가만). */
enum class BusinessType { INDIVIDUAL, CORPORATION }