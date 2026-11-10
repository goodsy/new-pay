package com.paycore.admin.merchantops.application.required

/**
 * MASTER 등록 명령 하나(MERCHANT-OPS-002 §3.2·§4.1 — B1-1·B1-2). 본문은 서명 범위다: 공통 필드(`idempotencyKey`·`actor`·`correlationId`) + [fields](명령 값 — 호출자가 형식 검사한 값만).
 * [fields] 값은 JSON 문자열(`String`)이고 [MasterCommandKind.arrays] 필드(계약 `scopeSiteIds`)만 문자열 배열(`List<String>`)이다. 필드 이름은 [MasterCommandKind.fields] 중 필수는 모두·선택([MasterCommandKind.optional])은
 * 있을 때만이다(없으면 본문에 키 자체가 없다).
 * [idempotencyKey]는 MASTER 멱등 키 원문(`mo1.<계정 ULID>.<submissionId>`)이라 **로그·감사·toString에 넣지 않는다**. [fields]에는 등록번호·채널 식별값이 있을 수 있어 toString에 값을 넣지 않는다.
 */
class MasterCommand(
    val kind: MasterCommandKind,
    val idempotencyKey: String,
    val actor: String,
    val correlationId: String,
    val fields: Map<String, Any>,
) {
    init {
        require(kind.fields.containsAll(fields.keys) && fields.keys.containsAll(kind.fields - kind.optional)) { "command fields must be ${kind.fields} (optional ${kind.optional})" }
        require(fields.all { (name, v) -> if (name in kind.arrays) v is List<*> && v.all { it is String } else v is String }) { "command values are strings (arrays: ${kind.arrays})" }
    }

    override fun toString(): String = "MasterCommand(kind=$kind, correlationId=$correlationId, fields=${fields.keys})"
}

/** 명령 종류 → MASTER 경로(`/internal/v1/admin/merchant-operations/<path>`)·명령 필드 이름(순서 = 본문 순서)·선택 필드·문자열 배열 필드(그 밖은 문자열). */
enum class MasterCommandKind(val path: String, val fields: Set<String>, val optional: Set<String> = emptySet(), val arrays: Set<String> = emptySet()) {
    REGISTER_BUSINESS("businesses", linkedSetOf("registrationNumber", "displayName")),
    REGISTER_MERCHANT("merchants", linkedSetOf("businessId", "displayName")),
    REGISTER_MERCHANT_SITE("merchant-sites", linkedSetOf("merchantId", "channelType", "channelIdentifier")),
    REGISTER_CONTRACT(
        "contracts", linkedSetOf("partyBusinessId", "scopeMerchantId", "scopeSiteIds", "effectiveFrom", "effectiveTo"),
        optional = setOf("effectiveTo"), arrays = setOf("scopeSiteIds"),
    ),
    ADD_SERVICE_AGREEMENT("service-agreements", linkedSetOf("contractId", "serviceKind")),

    /** B2-1 계약 Revision 등록(§9.2). 성공 reference `ctr_…#<n>`. */
    REGISTER_CONTRACT_REVISION(
        "contract-revisions", linkedSetOf("contractId", "scopeMerchantId", "scopeSiteIds", "effectiveFrom", "effectiveTo"),
        optional = setOf("effectiveTo"), arrays = setOf("scopeSiteIds"),
    ),
}
