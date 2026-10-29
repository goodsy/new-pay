package com.paycore.admin.approval.domain

/** 운영자 보정 취소 목적 종류(D-ADMIN-007, D-OPS-004 — PAYMENT `OperatorCancelPurpose`와 같은 계약 이름, 코드 공유 없음). */
enum class OperatorCancelPurposeKind {
    INCIDENT_OR_RECON_CORRECTION,
    MERCHANT_TERMINATED_OBLIGATION,
}
