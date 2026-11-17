package com.paycore.master.merchant.domain

/**
 * 사업자 진위·영업상태 확인 상태(TBD-06 — 연동 없음). **NOT_CHECKED 하나뿐**이다(TC-21): 어떤 경로도 확인 성공을 만들 수 없고 DB CHECK(`ck_business_verification`)도
 * NOT_CHECKED만 허용한다. 실제 연동(P1 F-010)이 값과 CHECK를 함께 넓힌다.
 */
enum class BusinessVerificationStatus { NOT_CHECKED }