package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.merchantops.application.provided.BusinessAddressInput
import com.paycore.admin.merchantops.application.provided.BusinessCategoryInput
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequestOutcome
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequests
import com.paycore.admin.merchantops.application.provided.BusinessContactInput
import com.paycore.admin.merchantops.application.provided.BusinessProfileContent
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import com.paycore.admin.merchantops.application.provided.BusinessRegistrationInput
import com.paycore.admin.merchantops.application.provided.BusinessRepresentativeInput
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.TestTotp
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BIZ-CRIT-APPROVAL-001 S3 사업자 중요정보 변경 승인 Named Fixture(요청·승인 Use Case 경로로만 — 직접 INSERT 없음, MASTER는 실제 HTTP 대역 [StubMasterOpsServer]). Spring bean이 아니다.
 * 명시 값(Demo): 법인 내용(상호 [NEW_NAME]), 사유 [REASON], 근거 참조 [EVIDENCE], MASTER 제안 응답 = 기준 version 0·항목 `displayName`·`h1` digest [DIGEST]·요청자 = 본문 actor 그대로(정상),
 * [proposerOverride]가 있으면 그 값(무결성 시험). A 단계 상태는 사업자별 [state]. 실행 응답은 [MerchantOpsApprovalFixture.executeReplies]를 공유한다(없으면 503).
 */
class BusinessChangeApprovalFixture(
    val base: MerchantOpsApprovalFixture,
    private val requests: BusinessChangeRequests,
    private val owners: OwnerServiceApprovalUseCase,
    private val path: ApprovalPath,
    private val storage: AdminAccountStorage,
) {
    private val json = JsonMapper.builder().build()
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 사업자 → MASTER 제안 응답(기본 성공). */
    val proposalReplies = ConcurrentHashMap<String, (ByteArray) -> Reply>()

    @Volatile
    var proposerOverride: String? = null

    /** 제안 응답의 항목·중요 대표자 변경·정정 여부(기본 `displayName`·N·N — S3 리뷰 ⑩ 감사 detail 시험이 바꾼다, [uninstall]·시험 afterEach가 되돌린다). */
    @Volatile
    var proposalFields: String = "\"displayName\""

    @Volatile
    var representativeChanged: Boolean = false

    @Volatile
    var subjectCorrection: Boolean = false

    fun resetProposal() {
        proposerOverride = null
        proposalFields = "\"displayName\""
        representativeChanged = false
        subjectCorrection = false
    }

    fun install() {
        base.install()
        StubMasterOpsServer.replyWith(PROPOSALS) { body ->
            val businessId = json.readTree(body).get("businessId").asString()
            proposalReplies[businessId]?.invoke(body) ?: Reply(200, proposed(body))
        }
        StubMasterOpsServer.replyWith(EXECUTE) { body -> base.executeReplies[base.idOf(body)]?.invoke(body) ?: Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
    }

    fun uninstall() {
        base.uninstall()
        StubMasterOpsServer.clearDynamic(PROPOSALS)
        StubMasterOpsServer.clearDynamic(EXECUTE)
        resetProposal()
    }

    fun proposed(body: ByteArray, fields: String = proposalFields, baseVersion: Long = 0): String {
        val actor = proposerOverride ?: json.readTree(body).get("actor").asString()
        val id = proposalIdOf(json.readTree(body).get("businessId").asString())
        return """{"outcome":"SUCCEEDED","reference":"$id","replayed":false,"proposal":{"proposalId":"$id","baseVersion":$baseVersion,"contentDigest":"$DIGEST",""" +
            """"changedFields":[$fields],"representativeChanged":$representativeChanged,"subjectCorrection":$subjectCorrection,"requestedBy":"$actor"}}"""
    }

    fun proposalIdOf(businessId: String) = "bcp_" + businessId.removePrefix("biz_")

    fun newBusinessId(): String = "biz_" + (1..26).map { alphabet[(Math.random() * 32).toInt()] }.joinToString("")

    /** A 단계 MASTER 상태(기본 version 0·ACTIVE·REGISTERED). */
    fun state(businessId: String, version: Long = 0, lifecycle: String = "ACTIVE", status: String = "REGISTERED", reply: Reply? = null) {
        StubMasterOpsServer.reply(statePath(businessId), reply ?: Reply(200, """{"version":$version,"lifecycle":"$lifecycle","registrationStatus":"$status"}"""))
    }

    fun original(businessId: String) {
        StubMasterOpsServer.reply(
            originalPath(businessId),
            Reply(
                200,
                """{"proposalId":"${proposalIdOf(businessId)}","businessId":"$businessId","content":{"businessType":"CORPORATION","displayName":"$NEW_NAME",""" +
                    """"registration":{"corporateRegistrationNumber":"1101110000001","openingDate":"2026-01-01","countryCode":"KR","englishName":null,"phoneNumber":"0212345678",""" +
                    """"email":"office@example.test"},"representatives":[{"name":"$CANARY_PERSON","type":"JOINT"}],"categories":[{"type":"BUSINESS_TYPE","name":"서비스업"},""" +
                    """{"type":"BUSINESS_ITEM","name":"전자상거래"}],"addresses":[{"type":"REGISTERED","postalCode":"06236","baseAddress":"서울특별시 강남구 테헤란로 1","detailAddress":null}],""" +
                    """"contacts":[{"name":"김담당","email":"contact@example.test","phoneNumber":"01012345678","primary":true}]}}""",
            ),
        )
    }

    fun content(name: String = NEW_NAME) = BusinessProfileContent(
        "CORPORATION", name, BusinessRegistrationInput("1101110000001", "2026-01-01", "KR", null, "0212345678", "office@example.test"),
        listOf(BusinessRepresentativeInput(CANARY_PERSON, "JOINT")), listOf(BusinessCategoryInput("BUSINESS_TYPE", "서비스업"), BusinessCategoryInput("BUSINESS_ITEM", "전자상거래")),
        listOf(BusinessAddressInput("REGISTERED", "06236", "서울특별시 강남구 테헤란로 1", null)), listOf(BusinessContactInput("김담당", "contact@example.test", "01012345678", true)),
    )

    @Suppress("LongParameterList")
    fun request(
        operator: AdminAccount, businessId: String, reason: String? = REASON, evidence: String? = EVIDENCE, version: Long = 0,
        submissionId: String = UUID.randomUUID().toString().replace("-", ""),
    ): BusinessChangeRequestOutcome = requests.request(
        operator.id.value, storage.load(operator.id).sessionGeneration,
        BusinessProfileRequest.Change(BusinessProfileOperation.UPDATE, businessId, submissionId, version, reason, content(), evidence), path.context(),
    )

    /** 적격 요청 — (승인 ID, 사업자 ID). */
    fun requested(operator: AdminAccount = base.operator(), businessId: String = newBusinessId()): Pair<String, String> {
        state(businessId)
        val outcome = request(operator, businessId)
        return ((outcome as? BusinessChangeRequestOutcome.Created) ?: error("fixture business change request failed: $outcome")).approvalId to businessId
    }

    fun approve(approvalId: String, approver: LoginReadyAccounts.LoginReady = base.approver(), revalidated: Boolean = true, totp: String? = null): ApprovalOutcome = owners.approve(
        DecideApproval(approvalId, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, totp ?: TestTotp.code(approver.secret), path.context(), revalidated),
    )

    fun succeeded(businessId: String): (ByteArray) -> Reply = { Reply(200, StubMasterOpsServer.succeeded(businessId)) }

    companion object {
        const val PROPOSALS = "/internal/v1/admin/businesses/commands/critical-change-proposals"
        const val EXECUTE = "/internal/v1/admin/merchant-operations/business-critical-changes"
        const val NEW_NAME = "새 법인 상호"
        const val REASON = "상호 변경 신고"
        const val EVIDENCE = "BIZ-DOC/2026-0001#p1"
        const val CANARY_PERSON = "캔어리대표"
        val DIGEST = "h1:" + "ab".repeat(32)

        fun statePath(businessId: String) = "/internal/v1/admin/businesses/$businessId/approval-state"
        fun originalPath(businessId: String) = "/internal/v1/admin/businesses/$businessId/critical-change-proposals/bcp_${businessId.removePrefix("biz_")}/original"
    }
}
