package com.paycore.admin.audit.application.provided

import java.time.Instant

/**
 * 감사 기록(§4.6). **호출자의 로컬 TX 안에서만**(MANDATORY) — 감사 저장이 실패하면 예외가 전파되어 상태 변경도 함께 rollback된다(감사 실패 = 행위 실패,
 * `AuditFailureClosesActionSpec`). 상태 변경 없는 행위도 이 기록이 실패하면 성공 응답을 내지 않는다.
 */
interface AdminAuditRecorder {
    fun record(record: AuditRecord)
}

/**
 * 감사 행 입력. [actorKind]: "ADMIN"·"ANONYMOUS"·"SYSTEM_BOOTSTRAP"·"SYSTEM_RECOVERY"·"SYSTEM_JOB"(만료 정리 job). [action]·[resultCode]는 고정 대문자 code, [detail]은 `key=value;…` 요약만.
 * 비밀번호·코드·시크릿·세션 ID·CSRF 토큰을 넣지 않는다.
 */
data class AuditRecord(
    val occurredAt: Instant,
    val actorKind: String,
    val actorId: String?,
    val actorDisplayName: String?,
    val action: String,
    val resultCode: String,
    val correlationId: String,
    val targetActorId: String? = null,
    val executorActorId: String? = null,
    val approvalId: String? = null,
    val remoteIp: String? = null,
    val loginNameHmac: ByteArray? = null,
    val detail: String? = null,
    /** 마스킹 해제 사유(UNMASK 행만 — 사용자 입력 원문, 감사 조회로 확인). */
    val reason: String? = null,
    /** 계정이 아닌 대상(ADMIN-PAYMENT-OPS-001 §4.10 — 소유 서비스 승인의 paymentId). [targetActorId]와 함께 쓰지 않는다. */
    val targetRef: String? = null,
    /** MERCHANT-CODE-001 MC-3a(§5.6.4 — C-32): 가맹점과 관련된 행의 내부 키(mer_). 대상 표기 [targetRef]는 같은 mer_ 또는 그 시점의 가맹점 코드. */
    val targetMerchantId: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is AuditRecord && toString() == other.toString() && targetMerchantId == other.targetMerchantId

    override fun hashCode(): Int = toString().hashCode()

    override fun toString(): String = "AuditRecord(action=$action, result=$resultCode, actor=$actorId, target=${targetActorId ?: targetRef}, at=$occurredAt, correlation=$correlationId)"
}
