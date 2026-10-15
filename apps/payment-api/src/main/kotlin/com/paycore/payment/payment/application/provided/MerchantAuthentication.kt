package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * 가맹점 공개 API Credential(D-CPA-001)의 keyId. 키 원문은 `pc_<test|live>_sk_<keyId>.<256bit 난수 base64url 43자>`이고 keyId는 그중
 * 조회용 식별자다(비밀 아님 — 로그·감사에 남겨도 된다). 형식은 8..32자 영숫자(`_`·`.` 없음: 원문 해석이 모호하지 않게).
 * 오류 메시지에 값을 넣지 않는다.
 */
@JvmInline
value class CredentialKeyId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "credential keyId must be 8..32 alphanumeric characters" }
    }

    companion object {
        val PATTERN = Regex("^[A-Za-z0-9]{8,32}$")
    }
}

/**
 * 인증된 가맹점 주체(CP-CONTRACT-001 §2 신뢰 컨텍스트). MID·environment는 **Credential에서** 결정되고 요청 본문으로 덮어쓰지 않는다.
 * v1 Credential 권한 범위는 공개 가맹점 API 전체다(D-CPA-001) — operation 축은 아직 없다.
 */
data class MerchantAuthentication(
    val credentialKeyId: CredentialKeyId,
    val merchantAccountId: MerchantAccountId,
    val environment: Environment,
)

/** 인증 실패 원인. **응답에서는 구분하지 않는다**(모두 401 UNAUTHENTICATED — 열거 방지). 지표·로그 분류에만 쓴다. */
enum class AuthenticationFailureReason {
    /** `Authorization` 헤더 없음. */
    MISSING,

    /** Bearer 형식·키 형식 밖. */
    MALFORMED,

    /** keyId에 해당하는 투영 행 없음. */
    UNKNOWN_KEY,

    /** 키 접두 환경(test/live)과 저장 환경이 다름. */
    ENVIRONMENT_MISMATCH,

    /** 비밀 검증값 불일치. */
    SECRET_MISMATCH,

    /** 폐기됨. */
    REVOKED,

    /** 유효 기간 밖(시작 전·종료 후). */
    NOT_VALID_NOW,

    /** 투영 lease 만료(전파가 끊겨 최신 폐기 여부를 확인할 수 없다 — fail-closed, D-CPA-001 "lease 상한"). */
    LEASE_EXPIRED,
}

sealed interface MerchantAuthenticationResult {
    data class Authenticated(val authentication: MerchantAuthentication) : MerchantAuthenticationResult

    data class Rejected(val reason: AuthenticationFailureReason) : MerchantAuthenticationResult
}

/**
 * 인증 정보 저장소가 지금 답할 수 없다(교착·잠금 대기·연결 — 검증 불가). 거절(401)로 거짓 판정하지 않는다: 웹 어댑터는 503
 * `TEMPORARILY_UNAVAILABLE`로 답한다. 웹이 `application.required`의 저장소 실패 타입을 보지 않게 provided 타입으로 옮긴다(관례 — 웹은
 * provided만). [kind]·[diagnostic]은 로그용 분류이며 메시지에 값을 넣지 않는다.
 */
class MerchantAuthenticationUnavailable(val kind: FailureKind, val diagnostic: StoreDiagnostic?, cause: Throwable) :
    RuntimeException("merchant credential store temporarily unavailable", cause)

/**
 * 가맹점 Bearer API 키 검증(D-CPA-001). PAYMENT 로컬 Credential 투영만 읽는다(MASTER 동기 호출 없음, 캐시 없음 — 폐기가 다음 요청부터
 * 보이게). 저장은 keyId + SHA-256 검증값뿐이며 원문 키를 저장·로그하지 않는다. 발급·전파는 MASTER-CREDENTIAL-001이다 — 그 전까지 운영
 * 투영은 비어 있어 모든 요청이 거절된다(fail-closed).
 */
interface AuthenticateMerchantUseCase {
    /**
     * [presentedKey]는 `Authorization: Bearer ` 뒤의 문자열 그대로다(없으면 null).
     * @throws MerchantAuthenticationUnavailable 저장소가 일시적으로 답할 수 없을 때(거절이 아니다).
     */
    fun authenticate(presentedKey: String?): MerchantAuthenticationResult
}
