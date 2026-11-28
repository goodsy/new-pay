package com.paycore.admin.merchantops.application.provided

/**
 * 화면 → ADMIN 계약 접수 건 명령 입력(MERCHANT-FLOW-001 §4B.7 — MASTER 본문 필드 + `submissionId`, "모든 key 필수·선택 null"). 구조(key 집합·자료형·열거·목록 길이)는 adapter가 엄격 해석했고,
 * 값 규칙(날짜·경로·담당자 형식·사유·근거 참조)은 Use Case가 `ContractIntakeInputRules`로 검사한다(즉시 안내용 1차 검사 — 권위는 MASTER).
 * 대상 [businessId]·[ContractIntakeRequest.Move.intakeSeq]는 경로 값이다. 모든 toString은 값을 가린다(사유·근거 원문 — 로그·예외 비노출).
 */
sealed interface ContractIntakeRequest {
    val operation: ContractIntakeOperation
    val businessId: String
    val submissionId: String
    val reason: String?

    /** 접수 건 생성(§4A.6 접수 항목). [expectedKind] = 화면이 판정한 종류(NEW·CHANGE — 잠금 뒤 판정과 다르면 MASTER `CONTRACT_INTAKE_KIND_MISMATCH`). [contractManagerId] = `adm_` 계정 키. */
    class Open(
        override val businessId: String,
        override val submissionId: String,
        val expectedKind: String,
        val receivedOn: String,
        val intakeChannel: String,
        val contractManagerId: String?,
        val desiredServices: List<String>,
        override val reason: String?,
    ) : ContractIntakeRequest {
        override val operation: ContractIntakeOperation get() = ContractIntakeOperation.OPEN

        override fun toString(): String = "ContractIntakeRequest.Open(kind=$expectedKind, ***)"
    }

    /**
     * FLOW-2d 접수 항목 수정(열린 건 — §4A.3 D-FLOW-007 ④·D-FLOW-009 ①). [intakeSeq] = 경로 값, [expectedVersion] = 대화상자를 연 때 본 version(고정).
     * [contractManagerId] = 화면 불투명 참조를 웹 adapter가 이 세션의 대응표로 푼 계정 키(풀리지 않으면 null — Use Case가 `contractManager NOT_ALLOWED`, D-GOV-024).
     */
    class UpdateItems(
        override val businessId: String,
        val intakeSeq: Int,
        override val submissionId: String,
        val expectedVersion: Long,
        val receivedOn: String,
        val intakeChannel: String,
        val contractManagerId: String?,
        val desiredServices: List<String>,
        override val reason: String?,
    ) : ContractIntakeRequest {
        override val operation: ContractIntakeOperation get() = ContractIntakeOperation.UPDATE_ITEMS

        override fun toString(): String = "ContractIntakeRequest.UpdateItems(version=$expectedVersion, ***)"
    }

    /** 전이(계약서 접수·되돌리기·계약 불가). [target] ∈ DOCUMENT_RECEIVED·CONSULTING·REJECTED. */
    class Move(
        override val businessId: String,
        val intakeSeq: Int,
        override val submissionId: String,
        val expectedVersion: Long,
        val target: String,
        override val reason: String?,
    ) : ContractIntakeRequest {
        override val operation: ContractIntakeOperation get() = ContractIntakeOperation.MOVE

        override fun toString(): String = "ContractIntakeRequest.Move(target=$target, version=$expectedVersion, ***)"
    }

    /** 계약 완료(사업자 계약 생성 또는 새 버전 — §4B.6). 근거 참조 필수, 시작일(KST 날짜) 필수, 종료일 선택, 범위 1개 이상. */
    class Complete(
        override val businessId: String,
        val intakeSeq: Int,
        override val submissionId: String,
        val expectedVersion: Long,
        val documentRef: String,
        val startDate: String,
        val endDate: String?,
        val services: List<String>,
        override val reason: String?,
    ) : ContractIntakeRequest {
        override val operation: ContractIntakeOperation get() = ContractIntakeOperation.COMPLETE

        override fun toString(): String = "ContractIntakeRequest.Complete(version=$expectedVersion, ***)"
    }

    /**
     * FLOW-2c 사업자 계약 정지·정지 해제·해지([operation] ∈ SUSPEND·RESUME·TERMINATE). [contractSeq] = 경로 값(화면이 본 최신 계약), [expectedEventCount] = 화면이 본 사건 수,
     * '사유 및 비고' [reason] 필수, 근거 참조 [evidenceRef] 선택(D-GOV-022 — 세 명령 같은 규칙).
     */
    class ContractEvent(
        override val operation: ContractIntakeOperation,
        override val businessId: String,
        val contractSeq: Int,
        override val submissionId: String,
        val expectedEventCount: Int,
        override val reason: String?,
        val evidenceRef: String?,
    ) : ContractIntakeRequest {
        init {
            require(operation.isContractEvent) { "contract event operations only" }
        }

        override fun toString(): String = "ContractIntakeRequest.ContractEvent(op=$operation, count=$expectedEventCount, ***)"
    }
}
