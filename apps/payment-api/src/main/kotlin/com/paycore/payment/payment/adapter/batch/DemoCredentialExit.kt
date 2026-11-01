package com.paycore.payment.payment.adapter.batch

/** `demo-credential` 종료 코드(DEMO-SEED-001 명세 §4.8 — `demo-seed`·internal-admin `OneShotExit`과 같은 번호 의미, 8~10은 seed 전용이라 없음 + 11). */
enum class DemoCredentialExit(val code: Int) {
    SUCCESS(0),
    FAILED(1),
    USAGE(2),
    MODE_REFUSED(3),
    CONDITION_REFUSED(4),
    LOST(5),
    OUTPUT_REFUSED(6),
    OUTPUT_FAILED(7),
    KEY_ID_COLLISION(11),
}
