package com.paycore.master.merchant.domain

/**
 * 희망 서비스(§4A.6 — D-FLOW-003 ③, D-FLOW-004 ③): **체크 기록만, 효력 없음**(가맹점 CSA·결제수단 개통(2인)과 무관 — 권리·Snapshot·Outbox 어디에도 쓰지 않는다).
 * 선택 가능한 두 가지만 둔다. 가상계좌는 화면에 '준비 전'으로만 보이고 선택할 수 없으므로 저장 값이 없다(선택 가능해지면 값·열을 함께 추가한다).
 */
enum class DesiredService { CARD_AUTH, CARD_KEYIN }
