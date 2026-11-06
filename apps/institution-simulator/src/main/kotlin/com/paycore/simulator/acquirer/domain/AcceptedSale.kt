package com.paycore.simulator.acquirer.domain

/** 이 매입사가 수락한 원 SALE(CANCEL 판정 입력 — 원거래 참조 대조·원승인별 취소 소비 누적의 키, 리뷰 MJ-1). */
data class AcceptedSale(val transactionId: String, val providerTransactionId: String, val authorizationEntryId: String)
