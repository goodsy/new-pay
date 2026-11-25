package com.paycore.master.merchant.domain

/**
 * 접수 경로 고정 목록(§4A.6 — D-FLOW-003 ⑤, D-FLOW-004 ③): 직접 영업·전화·홈페이지·영업대행사·기타. 이름은 영속 값이라 바꾸지 않는다.
 * [SALES_AGENCY]는 영업대행사 기준 목록(FLOW-4)이 생기기 전까지 저장하지 못한다 — FLOW-1 표에는 대행사 참조 열이 없고(리뷰 m-2) DB CHECK `ck_cin_channel`이
 * 이 값을 받지 않는다(FLOW-4 migration이 대행사 열·FK와 함께 연다). 서버 명령의 거절은 FLOW-2.
 */
enum class ContractIntakeChannel { DIRECT_SALES, PHONE, WEBSITE, SALES_AGENCY, OTHER }
