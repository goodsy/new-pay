package com.paycore.payment.payment.application.provided

/**
 * Payment 생성 시점 eligibility Snapshot 버전(CP-CONTRACT-001 §13, Auditability). eligibility 판정 자체는 required Port
 * `PaymentEligibilityPort` 구현(MASTER-SNAPSHOT-001/CP-API-001)이 하며, Payment에는 그 평가 결과(Eligible)의 값만 보존한다.
 * 1..64자 출력 가능 ASCII(영속 컬럼 VARCHAR(64)).
 */
@JvmInline
value class EligibilitySnapshotVersion(val value: String) {
    init {
        require(value.length in 1..64 && value.all { it in '!'..'~' }) { "eligibilitySnapshotVersion must be 1..64 printable ASCII characters" }
    }
}
