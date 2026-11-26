package com.paycore.admin.merchantops.application.required

import com.paycore.admin.merchantops.application.provided.ContractIntakeOperation

/**
 * MASTER 계약 접수 건 명령 호출 Port(MERCHANT-FLOW-001 §4B.7 — `POST /internal/v1/admin/businesses/contract-intakes/commands/{open|move|complete}`, 서명 채널).
 * 구현은 사업자 Gateway(`adapter.integration.HttpMasterBusinessGateway` — 같은 서명기·키·쓰기 permit·응답 상한, 허용 모양 fail-closed). **DB TX 안에서 부르면 `IllegalStateException`**.
 * 결과는 사업자 명령과 같은 값([MasterBusinessCommandResult] — existing은 항상 없음).
 */
interface MasterContractIntakeGateway {
    fun contractIntakeCommand(command: MasterContractIntakeCommand): MasterBusinessCommandResult
}

/** MASTER 계약 접수 건 명령 본문. [body]는 공통 3필드를 뺀 명령 필드 전부(MASTER "모든 key 필수·선택 null" — 순서 = MASTER 본문 순서). toString은 값을 가린다(사유·근거 원문). */
class MasterContractIntakeCommand(
    val operation: ContractIntakeOperation,
    val idempotencyKey: String,
    val actor: String,
    val correlationId: String,
    val body: Map<String, Any?>,
) {
    override fun toString(): String = "MasterContractIntakeCommand(operation=$operation, correlationId=$correlationId, ***)"
}
