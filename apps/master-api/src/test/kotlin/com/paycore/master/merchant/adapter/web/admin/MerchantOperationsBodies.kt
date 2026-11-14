package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.OnboardingFixture
import java.util.UUID

/**
 * MERCHANT-OPS-002 B1-1 명령 본문 Named Fixture(src/test 전용 — Test Fixture Policy: 업무 조건이 이름에 드러나게, 무효 Fixture는 의도를 이름에). 값은 시험 값이다(실제 사업자·도메인 아님).
 * ADMIN이 보내는 모양 그대로: 키 `mo1.<계정 ULID>.<submissionId>`, actor `admin:<계정 ID>`, correlationId `mos.<submissionId>`(C-4·C-14).
 */
object MerchantOperationsBodies {
    const val ACCOUNT = "adm_01JC00000000000000000000V1"
    const val BUSINESSES = "/internal/v1/admin/merchant-operations/businesses"
    const val MERCHANTS = "/internal/v1/admin/merchant-operations/merchants"
    const val SITES = "/internal/v1/admin/merchant-operations/merchant-sites"
    const val CONTRACTS = "/internal/v1/admin/merchant-operations/contracts"
    const val AGREEMENTS = "/internal/v1/admin/merchant-operations/service-agreements"
    const val CONTRACT_REVISIONS = "/internal/v1/admin/merchant-operations/contract-revisions"

    /** 한 번의 화면 제출(submissionId)에서 파생되는 MASTER 공통 필드. */
    class Submission(val submissionId: String = UUID.randomUUID().toString().replace("-", ""), account: String = ACCOUNT) {
        val idempotencyKey = "mo1.${account.removePrefix("adm_")}.$submissionId"
        val actor = "admin:$account"
        val correlationId = "mos.$submissionId"
    }

    fun registerBusiness(s: Submission = Submission(), brn: String = OnboardingFixture.registrationNumber().value, name: String = "시험 사업자"): String =
        obj(common(s) + listOf("registrationNumber" to brn, "displayName" to name))

    /** MERCHANT-CODE-001 MC-1: `merchantCode` 필수(공유 DB — 기본값은 시험마다 다른 형식 안 코드). */
    fun registerMerchant(
        businessId: String,
        s: Submission = Submission(),
        name: String = "시험 가맹점",
        code: String = com.paycore.master.merchant.domain.MerchantCodeFixtures.unique().value,
    ): String = obj(common(s) + listOf("businessId" to businessId, "displayName" to name, "merchantCode" to code))

    fun registerSite(merchantId: String, s: Submission = Submission(), type: String = "WEB", identifier: String = OnboardingFixture.host()): String =
        obj(common(s) + listOf("merchantId" to merchantId, "channelType" to type, "channelIdentifier" to identifier))

    /**
     * B1-2 계약 초안(Revision 1). [siteIds]가 비면 MERCHANT Scope. [from]·[to]는 ADMIN이 KST 날짜에서 만든 `…Z` ms 표기(C-7). 기본 시작 = 시험 clock t0(KST 2026-10-09)의 **다음 날** KST 00:00.
     * [to]가 null이면 필드를 보내지 않는다(종료 없음).
     */
    fun registerContract(
        partyBusinessId: String,
        scopeMerchantId: String,
        siteIds: List<String> = emptyList(),
        s: Submission = Submission(),
        from: String = KST_TOMORROW,
        to: String? = null,
    ): String = raw(
        common(s).map { (k, v) -> k to str(v) } + listOf(
            "partyBusinessId" to str(partyBusinessId),
            "scopeMerchantId" to str(scopeMerchantId),
            "scopeSiteIds" to siteIds.joinToString(",", "[", "]") { str(it) },
            "effectiveFrom" to str(from),
        ) + listOfNotNull(to?.let { "effectiveTo" to str(it) }),
    )

    /** B2-1 계약 Revision 등록(§9.2). [siteIds]가 비면 MERCHANT Scope. 기본 시작 = t0 다음 날 KST 00:00. [to]가 null이면 필드 없음. */
    fun registerContractRevision(
        contractId: String,
        scopeMerchantId: String,
        siteIds: List<String> = emptyList(),
        s: Submission = Submission(),
        from: String = KST_TOMORROW,
        to: String? = null,
    ): String = raw(
        common(s).map { (k, v) -> k to str(v) } + listOf(
            "contractId" to str(contractId),
            "scopeMerchantId" to str(scopeMerchantId),
            "scopeSiteIds" to siteIds.joinToString(",", "[", "]") { str(it) },
            "effectiveFrom" to str(from),
        ) + listOfNotNull(to?.let { "effectiveTo" to str(it) }),
    )

    fun addServiceAgreement(contractId: String, s: Submission = Submission(), kind: String = "CARD_PG_PAYMENT"): String =
        obj(common(s) + listOf("contractId" to contractId, "serviceKind" to kind))

    /** 시험 clock t0 = 2026-10-09T02:00Z(KST 10-09 11:00) 기준 KST 날짜 경계. 판정은 주입된 MASTER clock(@Primary MutableMasterClock)으로 하므로 벽시계 자정과 무관하다(리뷰 n-2 — 쓰는 spec은 clock을 t0로 되돌린다). */
    const val KST_TODAY = "2026-10-08T15:00:00.000Z"
    const val KST_TOMORROW = "2026-10-09T15:00:00.000Z"
    const val KST_YESTERDAY = "2026-10-07T15:00:00.000Z"

    /** 무효: Site 51개(C-2 상한 50 초과). */
    fun withTooManyScopeSites(partyBusinessId: String, scopeMerchantId: String): String =
        registerContract(partyBusinessId, scopeMerchantId, (1..51).map { "site_01JC" + it.toString().padStart(22, '0') })

    /** 무효: 하이픈 표기 등록번호(D-MD-008 — 정규화하지 않고 거절). */
    fun withHyphenatedBrn(): String = registerBusiness(brn = "778-12-34560")

    /** 무효: 대문자 host(D-MD-009 — 소문자 정규형만, 자동 정규화 없음). */
    fun withUpperCaseHost(merchantId: String): String = registerSite(merchantId, identifier = "Shop.Example.test")

    /** 무효: ADMIN 이름공간이 아닌 actor(§3.1 — `admin:` 접두만 채널로). */
    fun withActor(actor: String): String = registerBusiness().replace("\"actor\":\"admin:$ACCOUNT\"", "\"actor\":\"$actor\"")

    private fun common(s: Submission) = listOf("idempotencyKey" to s.idempotencyKey, "actor" to s.actor, "correlationId" to s.correlationId)

    private fun obj(fields: List<Pair<String, String>>): String = raw(fields.map { (k, v) -> k to str(v) })

    /** JSON 문자열 리터럴(시험 값 — 따옴표·역슬래시만 이스케이프). */
    fun str(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 값이 이미 JSON 표기인 필드로 객체를 만든다. */
    fun raw(fields: List<Pair<String, String>>): String = fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
}
