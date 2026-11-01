package com.paycore.payment.payment.application.provided

import java.time.Instant

/**
 * DEMO 배포 전용 Demo API key 발급(DEMO-SEED-001 §4.5 — D-DEMO-002·004). **운영 Credential 발급이 아니다**(운영은 MASTER-CREDENTIAL-001). TEST 키 하나를 PAYMENT
 * Credential 투영에 기록하고 고정 1회성·감사 행을 같은 PAYMENT 로컬 TX에서 커밋한다. 유효 7일, 갱신·재발급 없음. 명령 프로세스에만 빈이 있다.
 * 결과의 원문 키는 커밋 뒤 한 번만 돌려주고(출력 채널 전용) 어디에도 저장·로그하지 않는다.
 */
interface DemoCredentialIssueUseCase {
    fun issue(command: DemoCredentialIssueCommand): DemoCredentialIssueResult
}

/** [merchantAccountId]: 대상 MID(형식은 [MID_FORMAT]). [correlationId]: 실행 추적용(감사 행). */
data class DemoCredentialIssueCommand(val merchantAccountId: String, val correlationId: String) {
    init {
        require(MID_FORMAT.matches(merchantAccountId)) { "merchantAccountId format" }
        require(correlationId.length in 1..64) { "correlationId length" }
    }

    companion object {
        /** MASTER MID 형식(`mid_` + ULID 26자). */
        val MID_FORMAT = Regex("^mid_[0-9A-Z]{26}$")
    }
}

sealed interface DemoCredentialIssueResult {
    /** 커밋됨. [apiKey]는 원문(`pc_test_sk_<keyId>.<secret>`) — toString에 넣지 않는다. */
    class Issued(val merchantAccountId: String, val keyId: String, val apiKey: String, val validUntil: Instant) : DemoCredentialIssueResult {
        override fun toString(): String = "Issued(keyId=$keyId, validUntil=$validUntil)"
    }

    /** 사전 조건 거부(DB 무변경). */
    data class Refused(val reason: DemoCredentialRefusal) : DemoCredentialIssueResult

    /** 1회성 행 경합 패배(동시 실행 — rollback). */
    data object Lost : DemoCredentialIssueResult

    /** 투영 keyId PK 충돌(rollback — 발급 행도 없음, 재실행 시 새 keyId). LOST와 다르다. */
    data object KeyIdCollision : DemoCredentialIssueResult
}

enum class DemoCredentialRefusal {
    /** MID가 PAYMENT 최신 권리 Snapshot에 없다. */
    MID_UNKNOWN,

    /** MID의 정책 Assignment가 0개이거나 하나라도 DEMO가 아니다(m5 — 운영 내용이 생길 미래 버전·다른 가맹점 MID에 Demo 키를 붙이지 않는다). */
    NOT_DEMO_MERCHANT,

    /** 이미 발급했다(재발급 없음 — D-DEMO-004). 원문은 다시 보이지 않는다. */
    ALREADY_ISSUED,
}
