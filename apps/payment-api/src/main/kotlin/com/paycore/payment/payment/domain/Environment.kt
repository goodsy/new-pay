package com.paycore.payment.payment.domain

/**
 * 주문 환경(D-CPD-001, 사용자 확정 2026-10-06). Simulator/실기관 구분은 environment가 아니라 기관 Route 정보로 별도 관리하며
 * TEST는 실기관으로 전송하지 않는다(Route 규칙은 AUTH/연동 범위).
 */
enum class Environment { TEST, LIVE }
