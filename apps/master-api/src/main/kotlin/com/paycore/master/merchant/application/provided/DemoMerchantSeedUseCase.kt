package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.RouteRef
import java.time.Instant

/**
 * DEMO 배포 모드 전용 Demo 가맹점 seed(DEMO-SEED-001, D-DEMO-001·003·005). 기존 관리 명령 Use Case 18개를 정해진 순서로 부른다 — 단계마다 그 명령의
 * MASTER 로컬 TX이고 이 Use Case는 TX를 열지 않는다(중간 실패는 같은 명령 재실행으로 이어 간다). 멱등키는 `<keyPrefix>:<NN>-<slug>`로 결정적이라
 * 같은 내용 재실행은 재생, 다른 내용은 [DemoMerchantSeedResult.ContentConflict]다.
 *
 * 배치 모드(DEMO) 판정은 이 Use Case가 하지 않는다 — 명령 adapter가 컨텍스트 생성 전에 끝낸다(명세 §4.6). 값은 호출자(adapter의 Demo 상수)가 준다.
 */
interface DemoMerchantSeedUseCase {
    fun seed(command: DemoMerchantSeedCommand): DemoMerchantSeedResult
}

/**
 * [keyPrefix]: 멱등키 공간(운영 명령은 `demo-seed-v1` 고정, 1~33자 `[A-Za-z0-9-_.]`). [effectiveFrom]은 Asia/Seoul 00:00(D-MS2-002)이어야 한다.
 * [correlationId]는 실행 추적용(fingerprint 대상 아님).
 */
data class DemoMerchantSeedCommand(
    val keyPrefix: String,
    val actor: ActorRef,
    val correlationId: String,
    val effectiveFrom: Instant,
    val values: DemoMerchantValues,
) {
    init {
        require(keyPrefix.length in 1..MAX_PREFIX && keyPrefix.all { it.isLetterOrDigit() && it.code < ASCII || it in "-_." }) {
            "keyPrefix must be 1..$MAX_PREFIX characters of [A-Za-z0-9-_.]"
        }
    }

    private companion object {
        /** 가장 긴 단계 접미(`:14-register-settlement-version` 31자)와 합쳐 멱등키 64자 이하. */
        const val MAX_PREFIX = 33
        const val ASCII = 128
    }
}

/** Demo 값(운영값 아님). 한도는 D-DEMO-003, 정책은 내용 없는 DEMO 버전(D-MS2-003)으로 등록된다. */
data class DemoMerchantValues(
    val registrationNumber: BusinessRegistrationNumber,
    val businessName: DisplayName,
    val merchantName: DisplayName,
    /** MERCHANT-CODE-001 C-6: 새 DB seed의 가맹점 코드(Demo — 운영값 아님). */
    val merchantCode: MerchantCode,
    val siteHost: String,
    val evidence: EvidenceRef,
    val reason: DecisionReason,
    val currency: CurrencyCode,
    val perTransactionLimit: LimitValue,
    val dailyLimit: LimitValue,
    val feePolicyCode: PolicyCode,
    val settlementPolicyCode: PolicyCode,
    val acquirerCode: AcquirerCode,
    val routeRef: RouteRef,
    val institutionMerchantRef: InstitutionMerchantRef,
)

/** seed 단계(번호는 멱등키의 일부 — 첫 배포 뒤 바꾸지 않는다). [carriesEffectiveFrom] = 효력 시작에 T0를 쓰는 단계(사전 검사 대상). */
enum class DemoSeedStep(val number: Int, val slug: String, val operation: AdminOperation, val carriesEffectiveFrom: Boolean = false) {
    REGISTER_BUSINESS(1, "register-business", AdminOperation.REGISTER_BUSINESS),
    REGISTER_MERCHANT(2, "register-merchant", AdminOperation.REGISTER_MERCHANT),
    REGISTER_SITE(3, "register-site", AdminOperation.REGISTER_MERCHANT_SITE),
    REGISTER_CONTRACT(4, "register-contract", AdminOperation.REGISTER_CONTRACT, carriesEffectiveFrom = true),
    APPROVE_CONTRACT(5, "approve-contract", AdminOperation.APPROVE_CONTRACT_REVISION),
    ADD_AGREEMENT(6, "add-agreement", AdminOperation.ADD_SERVICE_AGREEMENT),
    ISSUE_MID(7, "issue-mid", AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT),
    REGISTER_ACTIVATION(8, "register-activation", AdminOperation.REGISTER_PAYMENT_METHOD_ACTIVATION),
    APPROVE_ACTIVATION(9, "approve-activation", AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION),
    APPROVE_SITE_REVIEW(10, "approve-site-review", AdminOperation.DECIDE_SITE_REVIEW),
    ENABLE_CURRENCY(11, "enable-currency", AdminOperation.ENABLE_CURRENCY, carriesEffectiveFrom = true),
    REGISTER_LIMIT(12, "register-limit", AdminOperation.REGISTER_TRANSACTION_LIMIT_POLICY, carriesEffectiveFrom = true),
    REGISTER_FEE_VERSION(13, "register-fee-version", AdminOperation.REGISTER_POLICY_VERSION),
    REGISTER_SETTLEMENT_VERSION(14, "register-settlement-version", AdminOperation.REGISTER_POLICY_VERSION),
    ASSIGN_FEE(15, "assign-fee", AdminOperation.ASSIGN_POLICY, carriesEffectiveFrom = true),
    ASSIGN_SETTLEMENT(16, "assign-settlement", AdminOperation.ASSIGN_POLICY, carriesEffectiveFrom = true),
    REGISTER_CARD_ROUTE(17, "register-card-route", AdminOperation.REGISTER_CARD_ROUTE_ACTIVATION),
    APPROVE_CARD_ROUTE(18, "approve-card-route", AdminOperation.APPROVE_CARD_ROUTE_ACTIVATION),
    ;

    fun key(prefix: String): AdminIdempotencyKey = AdminIdempotencyKey("$prefix:" + number.toString().padStart(2, '0') + "-" + slug)
}

/** 효력 시작 사전 검사 거부 이유(명세 §4.3 — DB 무변경). */
enum class EffectiveFromRefusal { EFFECTIVE_FROM_PASSED, EFFECTIVE_FROM_TOO_SOON }

sealed interface DemoMerchantSeedResult {
    /** 18단계 모두 성공. [replayed] = 모든 단계가 저장 결과 재생이었다. */
    data class Done(
        val merchantId: MerchantId,
        val merchantAccountId: MerchantAccountId,
        val siteId: MerchantSiteId,
        val effectiveFrom: Instant,
        val replayed: Boolean,
    ) : DemoMerchantSeedResult

    data class EffectiveFromRefused(val reason: EffectiveFromRefusal) : DemoMerchantSeedResult

    /** 업무 거절(저장·재생된다 — 같은 명령으로 다시 해도 같다). */
    data class StepRejected(val step: DemoSeedStep, val rejection: MasterRejection) : DemoMerchantSeedResult

    /** 같은 키에 다른 내용(이미 다른 값으로 seed됨). 그 단계는 아무것도 저장하지 않았다. */
    data class ContentConflict(val step: DemoSeedStep) : DemoMerchantSeedResult

    /** 결과를 바꾸지 않는 기술 실패(진행 중·일시 불가·커밋 불명확·ID 충돌). 같은 명령으로 다시 실행한다. */
    data class RetryLater(val step: DemoSeedStep) : DemoMerchantSeedResult
}
