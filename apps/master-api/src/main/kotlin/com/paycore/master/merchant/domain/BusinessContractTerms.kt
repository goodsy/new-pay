package com.paycore.master.merchant.domain

import java.time.LocalDate

/**
 * 계약 완료 때 운영자가 넣는 버전 조건(MERCHANT-FLOW-001 §4B.3·§4B.6): 시작일(필수, KST 날짜), 종료일(선택), 서비스 범위(1개 이상 — 화면은 카드 PG 결제를 체크 상태로 보여 주되
 * 희망 서비스에서 자동 복사하지 않는다, BR-BIZ-330). 입력 묶음일 뿐이다 — 소급·기간·범위 규칙은 [BusinessContract]가 판정 시각으로 검사하고 고정 거절 code를 낸다.
 */
data class BusinessContractTerms(val startDate: LocalDate, val endDate: LocalDate?, val services: Set<ServiceKind>)
