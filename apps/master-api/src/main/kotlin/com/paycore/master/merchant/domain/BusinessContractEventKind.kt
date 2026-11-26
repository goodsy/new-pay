package com.paycore.master.merchant.domain

/** 사업자 계약 사건 종류(MERCHANT-FLOW-001 §4B.2 — BR-BIZ-336): 정지·정지 해제·해지. 이름은 영속 값(DB CHECK `ck_bce_kind`)이라 바꾸지 않는다. */
enum class BusinessContractEventKind { SUSPEND, RESUME, TERMINATE }
