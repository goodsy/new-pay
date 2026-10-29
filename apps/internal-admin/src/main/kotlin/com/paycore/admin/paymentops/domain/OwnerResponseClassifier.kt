package com.paycore.admin.paymentops.domain

/**
 * PAYMENT 실행 POST 응답 → ADMIN 결과 분류(ADMIN-PAYMENT-OPS-001 §4.15, I-21, D-OPS-003·007 — 계약 golden ③ `responseCases`가 기대 상태·경보를 고정).
 * 불변식(§4.4.1): PAYMENT의 409·404는 커밋된 결정 행이 뒷받침하고 400은 결정적 형식 판정이다. 그래서 409(파생 2종 제외)·404·400만 종료(OWNER_REJECTED)이고,
 * 그 밖은 모두 결과 불명확(EXECUTION_UNKNOWN — 확정 응답만 종료). 파생 2종은 종료가 아니라 무결성 플래그 + 경보(D-OPS-007).
 */
object OwnerResponseClassifier {
    enum class Kind { STARTED, OWNER_REJECTED, UNKNOWN }

    /**
     * [code]: OWNER_REJECTED면 거절 code, UNKNOWN이면 실패 분류. [integrityFlag]: 실행 행 무결성 플래그(파생 2종). [alert]: **무결성 경보**(golden ③
     * `expectedIntegrityAlert` — 파생 409·400 형식, ADMIN·PAYMENT 내용 불일치 의심). [operationalAlert]: 운영 경보(401 키·시각, `APPROVAL_WINDOW_INVALID` 설정 순서 위반 —
     * §4.15, golden의 무결성 경보와 구별 — I-97).
     */
    data class Classification(val kind: Kind, val code: String?, val integrityFlag: String?, val alert: Boolean, val operationalAlert: Boolean = false)

    val DERIVED: Set<String> = setOf("APPROVAL_CONFLICT", "APPROVAL_IDENTITY_MISMATCH")

    /**
     * PAYMENT가 400으로 답하는 code 전체(S7 리뷰 M-1(c) — `PaymentOpsContractGoldenSpec`이 golden과 일치를 고정): 본문 형식 4종(golden ③), 서명 전 query 거부
     * `QUERY_NOT_ALLOWED`(golden ②). 본문 읽기 실패 `REQUEST_UNREADABLE`은 S7 리뷰 L-B로 PAYMENT 503(재시도 가능 — I-124)이라 여기 없다. 모두 **결정 행 없이** 끝난다
     * (§4.4.1 — 400은 결정적 형식 판정). 아래 400 경로는
     * 이 밖의 code도 OWNER_REJECTED로 두되(형식 거절은 효과 없음), 이 집합 밖 code로 끝난 승인은 대사 전체 스캔이 `MISSING_DECISION`으로 드러낸다(I-120).
     */
    val PAYMENT_BAD_REQUEST_CODES: Set<String> = setOf(
        "REQUEST_MALFORMED", "APPROVAL_ID_MALFORMED", "PAYMENT_ID_MALFORMED", "OPERATOR_ID_MALFORMED", "QUERY_NOT_ALLOWED",
    )
    private val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")

    /**
     * [status]와 응답 본문의 `error` code([errorCode] — 없거나 형식이 아니면 null). 200은 [cancellationIdValid]일 때만 STARTED(아니면 응답 해석 불가 → UNKNOWN).
     */
    fun classify(status: Int, errorCode: String?, cancellationIdValid: Boolean = false): Classification {
        val code = errorCode?.takeIf { CODE.matches(it) }
        return when {
            status == OK -> if (cancellationIdValid) Classification(Kind.STARTED, null, null, false) else unknown("RESPONSE_UNREADABLE")
            status == CONFLICT && code != null && code in DERIVED -> Classification(Kind.UNKNOWN, code, code, true)
            status == CONFLICT && code != null -> Classification(Kind.OWNER_REJECTED, code, null, false, operationalAlert = code == "APPROVAL_WINDOW_INVALID")
            status == NOT_FOUND && code == "PAYMENT_NOT_FOUND" -> Classification(Kind.OWNER_REJECTED, code, null, false)
            status == BAD_REQUEST && code != null -> Classification(Kind.OWNER_REJECTED, code, null, true)
            status == UNAUTHORIZED -> Classification(Kind.UNKNOWN, "UNAUTHENTICATED", null, false, operationalAlert = true)
            status in 400..499 -> unknown("HTTP_$status")
            status in 500..599 -> unknown(code?.let { "UNAVAILABLE_$it".take(MAX_CODE) } ?: "HTTP_$status")
            else -> unknown("HTTP_$status")
        }
    }

    fun unknown(failure: String): Classification = Classification(Kind.UNKNOWN, failure, null, false)

    private const val OK = 200
    private const val BAD_REQUEST = 400
    private const val UNAUTHORIZED = 401
    private const val NOT_FOUND = 404
    private const val CONFLICT = 409
    private const val MAX_CODE = 48
}
