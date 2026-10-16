package com.paycore.master.merchant.domain

/**
 * 생명주기(D-MD-006): ACTIVE / TERMINATED(비가역). 신규 결제 중지는 생명주기가 아니라 별도 차단 기록이다(사유·행위자, 여럿 독립 —
 * MASTER-LIFECYCLE-001). 종료 명령은 이번 범위가 아니라 전이 메서드가 없다.
 */
enum class Lifecycle { ACTIVE, TERMINATED }
