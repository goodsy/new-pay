package com.paycore.master.merchant.domain

/**
 * 접수 건 종류(MERCHANT-FLOW-001 §4B.6 — BR-BIZ-324·325, D-GOV-014 ①, D-GOV-018 ①ⓔ). 이름은 영속 값(DB CHECK `ck_cin_kind`)이라 바꾸지 않는다.
 * - [NEW](신규): 접수 생성 때 그 사업자의 최신 사업자 계약이 없거나 해지·만료 — 계약 완료가 새 사업자 계약 + 버전 1을 만든다.
 * - [CHANGE](변경): 최신 사업자 계약이 효력 전·유효·정지('계약 있음') — 계약 완료가 같은 계약에 새 버전을 더한다(대상 계약 순번을 함께 저장).
 * 운영자가 고르지 않는다 — 사업자 행 잠금 뒤 시스템이 판정하고, 화면이 본 종류(expectedKind)와 다르면 거절한다(`CONTRACT_INTAKE_KIND_MISMATCH`).
 */
enum class ContractIntakeKind { NEW, CHANGE }
