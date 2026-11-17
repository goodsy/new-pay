package com.paycore.payment.entitlement.domain

/**
 * 해석하는 Snapshot 계약 버전. 1 = 권리(MASTER-SNAPSHOT-001), 2 = 1 + 승인 시작 조건(MASTER-SNAPSHOT-002 명세 §4), 3 = 2 + Site 채널(AUTHN-001 §9),
 * 4 = 3 + 가맹점 코드(MERCHANT-CODE-001 §6 — 출력 전용 표시값, 권리 판정 입력 아님). 다른 버전은 원문만 보존하고
 * 판정에 쓰지 않는다(fail-closed, 명세 §3.5). v1 head로는 결제 생성 판정만 하고 승인 시작은 TERMS_NOT_PROPAGATED(fail-closed).
 */
val SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS: Set<Int> = setOf(1, 2, 3, 4)

/** 승인 시작 조건을 싣는 첫 schemaVersion. */
const val TERMS_SNAPSHOT_SCHEMA_VERSION: Int = 2

/** Site 채널(`sites[].channel` — WEB host / APP type)을 싣는 첫 schemaVersion(AUTHN-001 §9, D-AUTHN-002). v1·v2 head로는 결제창 host를 정할 수 없다(fail-closed). */
const val CHANNEL_SNAPSHOT_SCHEMA_VERSION: Int = 3

/**
 * 가맹점 코드(최상위 `merchantCode`)를 싣는 첫 schemaVersion(MERCHANT-CODE-001 §6, D-MCODE-001 Q-MC-1·D-MCODE-002 Q-MC-8). 웹훅·결제 응답의 출력 전용 값이며
 * 판정에 쓰지 않는다. v1~v3 head에는 코드가 없다(외부 필드 생략).
 */
const val MERCHANT_CODE_SNAPSHOT_SCHEMA_VERSION: Int = 4

/** 가맹점 코드 형식(MASTER `MerchantCode`·V13 CHECK와 같은 문법 — PAYMENT가 다시 확인한다, 정규화 없음). */
private val MERCHANT_CODE_FORMAT = Regex("^[a-z][a-z0-9]{5,13}$")

fun isMerchantCodeFormat(value: String): Boolean = MERCHANT_CODE_FORMAT.matches(value)

fun isSupportedSnapshotSchema(schemaVersion: Int?): Boolean = schemaVersion in SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS

/** 계약 위반(구조·값 형식·hash 불일치). 수신 adapter는 4xx로 거절하고 저장하지 않는다(MASTER는 격리). 메시지에 입력값을 넣지 않는다. */
class SnapshotContractViolation(message: String) : RuntimeException(message, null, false, false)
