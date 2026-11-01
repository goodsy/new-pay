package com.paycore.master.merchant.adapter.batch

/** `demo-seed` 종료 코드(DEMO-SEED-001 명세 §4.8 — internal-admin `OneShotExit` 0~8과 같은 의미 + 9·10). */
enum class DemoSeedExit(val code: Int) {
    SUCCESS(0),
    FAILED(1),
    USAGE(2),
    MODE_REFUSED(3),
    CONDITION_REFUSED(4),
    OUTPUT_REFUSED(6),
    OUTPUT_FAILED(7),
    RETRY_LATER(8),
    CONTENT_CONFLICT(9),
    STEP_REJECTED(10),
}
