package com.paycore.master.merchant.domain

/**
 * 사업자 등록 상태(D-BIZ-001 — 생명주기 [Lifecycle]과 별개 축). DRAFT = 임시저장, REGISTERED = 내부 기본정보 등록 완료(진위확인·KYB·계약 승인 아님 — TBD-02),
 * INACTIVE = 내부 관리 상태(TBD-08 — 이번에는 전이 경로 없음, DB CHECK 값만). 영속 값이라 이름을 바꾸지 않는다.
 */
enum class BusinessRegistrationStatus { DRAFT, REGISTERED, INACTIVE }