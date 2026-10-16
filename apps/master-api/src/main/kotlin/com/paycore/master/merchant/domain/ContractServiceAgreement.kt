package com.paycore.master.merchant.domain

import java.time.Instant

/** 서비스 종류(D-MD-002: CSA 1건 = 서비스 종류 1개). 초기 범위는 국내 신용카드 PG 결제 서비스(초기 §출시 범위). */
enum class ServiceKind { CARD_PG_PAYMENT }

/** 서비스 계약(CSA, D-MD-001). Contract 1:N. MID 발급 근거·정책 Assignment 기준. */
data class ContractServiceAgreement(
    val id: ContractServiceAgreementId,
    val serviceKind: ServiceKind,
    val addedAt: Instant,
    val addedBy: ActorRef,
)
