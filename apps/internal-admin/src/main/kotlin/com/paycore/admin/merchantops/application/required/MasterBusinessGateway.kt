package com.paycore.admin.merchantops.application.required

import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria

/**
 * MASTER 사업자 내부 API 호출 Port(BUSINESS-PROFILE-001 §7.2 Gateway — 구현 `adapter.integration.HttpMasterBusinessGateway`: 기존 서명기·키(D-MOPS-005)·redirect NEVER, 응답 상한
 * 조회 32 KiB·명령 8 KiB, 허용 모양 fail-closed). **DB TX 안에서 부르면 `IllegalStateException`**. 경로 변수·본문 값은 호출자가 형식 검사한 값만 넣는다.
 * 중첩 JSON 명령이라 문자열 전용 [MasterCommand]를 확장하지 않는다(C-17). 실패는 예외가 아니라 값이다.
 */
interface MasterBusinessGateway {
    /** 목록(§6.2). null 필드는 **보내지 않는다**(MASTER는 생략만 허용 — BIZ-2 인계 ④). */
    fun search(criteria: BusinessSearchCriteria): MasterCallResult

    /** 번호 정확 일치(§6.3 — POST 본문 `{"registrationNumber"}`만). */
    fun lookup(registrationNumber: String): MasterCallResult

    /** 상세(§6.4). 404 `BUSINESS_NOT_FOUND` → [MasterCallResult.NotFound]. */
    fun detail(businessId: String): MasterCallResult

    /** 이력(§6.5 — `/history/after/{seq}`). 404 `BUSINESS_NOT_FOUND` → [MasterCallResult.NotFound]. */
    fun history(businessId: String, afterSeq: Long): MasterCallResult

    /** 명령(§6.6·§6.7 — 쓰기 permit, 매 호출 새 서명 시각). */
    fun command(command: MasterBusinessCommand): MasterBusinessCommandResult
}

/**
 * MASTER 사업자 명령 본문. [body]는 공통 3필드를 뺀 명령 필드 전부(MASTER "모든 key 필수·선택 null" — 순서 = MASTER 본문 순서). toString은 값을 가린다(개인정보·번호).
 */
class MasterBusinessCommand(
    val operation: BusinessProfileOperation,
    val idempotencyKey: String,
    val actor: String,
    val correlationId: String,
    val body: Map<String, Any?>,
) {
    override fun toString(): String = "MasterBusinessCommand(operation=$operation, correlationId=$correlationId, ***)"
}

/**
 * MASTER 사업자 명령 결과(값).
 * - [Decided]: MASTER가 저장한 판정(200). 중복 거절이면 [Decided.existing](요약 4키 — D-BIZ-003), 다른 거절에는 없다(오면 해석 불가 = [Unknown]).
 * - [Invalid]: 400 `BUSINESS_VALIDATION_FAILED` + 필드 오류(모양 검사 통과분) — **효과 없음 확정**(TX 전 판정 또는 rollback — C-16 보충, OUTCOME_UNKNOWN 아님).
 * - [KeyReused]: 409 `IDEMPOTENCY_KEY_REUSED`. [Busy]: 쓰기 permit 없음(ADMIN·MASTER). [RetrySameKey]: 503 `REQUEST_IN_PROGRESS`·`OUTCOME_UNKNOWN`·`TEMPORARILY_UNAVAILABLE`
 *   (중복 요약 조회 실패 포함 — m-1, 같은 키 재전송이 재생 + 재조회). [Unknown]: timeout·연결 끊김·해석 불가·그 밖 상태 — 효과 유무를 모른다.
 */
sealed interface MasterBusinessCommandResult {
    data class Decided(val succeeded: Boolean, val reference: String?, val rejection: String?, val replayed: Boolean, val existing: Map<String, Any?>?) : MasterBusinessCommandResult {
        init {
            require(succeeded == (reference != null && rejection == null && existing == null)) { "a success has a reference only" }
            require(!succeeded == (rejection != null && reference == null)) { "a rejection has a code and no reference" }
        }

        /** NIT-5: existing의 상호는 가린다. */
        override fun toString(): String =
            "Decided(succeeded=$succeeded, reference=$reference, rejection=$rejection, replayed=$replayed, existing=${if (existing == null) "null" else "***"})"
    }

    data class Invalid(val fieldErrors: List<BusinessFieldError>) : MasterBusinessCommandResult

    data object KeyReused : MasterBusinessCommandResult

    data class Busy(val reason: String) : MasterBusinessCommandResult

    data class RetrySameKey(val reason: String) : MasterBusinessCommandResult

    data class Unknown(val reason: String) : MasterBusinessCommandResult
}
