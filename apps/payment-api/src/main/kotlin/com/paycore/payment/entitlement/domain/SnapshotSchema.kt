package com.paycore.payment.entitlement.domain

/**
 * 해석하는 Snapshot 계약 버전. 1 = 권리(MASTER-SNAPSHOT-001), 2 = 1 + 승인 시작 조건(MASTER-SNAPSHOT-002 명세 §4). 다른 버전은 원문만 보존하고
 * 판정에 쓰지 않는다(fail-closed, 명세 §3.5). v1 head로는 결제 생성 판정만 하고 승인 시작은 TERMS_NOT_PROPAGATED(fail-closed).
 */
val SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS: Set<Int> = setOf(1, 2)

/** 승인 시작 조건을 싣는 첫 schemaVersion. */
const val TERMS_SNAPSHOT_SCHEMA_VERSION: Int = 2

fun isSupportedSnapshotSchema(schemaVersion: Int?): Boolean = schemaVersion in SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS

/** 계약 위반(구조·값 형식·hash 불일치). 수신 adapter는 4xx로 거절하고 저장하지 않는다(MASTER는 격리). 메시지에 입력값을 넣지 않는다. */
class SnapshotContractViolation(message: String) : RuntimeException(message, null, false, false)
