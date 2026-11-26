package com.paycore.master.merchant.domain

/** 버전 단계(MERCHANT-FLOW-001 §4B.2 — GOV-MER-ELIG-001 §2.3, 계산 값): 시작 전 [SCHEDULED](예정), 시작 ≤ t < 실제 끝 [APPLYING](적용 중), 그 뒤 [ENDED](끝남). */
enum class BusinessContractVersionPhase { SCHEDULED, APPLYING, ENDED }
