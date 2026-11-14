package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestMerchantCurrencyEnablement
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import java.time.LocalDate
import java.time.ZoneId

/**
 * B3-2 통화 활성화 승인 Named Fixture(MERCHANT-OPS-002 §9.16·§9.26 — 요청·승인 Use Case 경로로만, 직접 INSERT 없음). 승인·결정 조회·기한 확인 응답 표는 [base]
 * ([MerchantOpsApprovalFixture] — approvalId별)를 공유하고 `currency-enablements` 실행 경로를 같은 표에 연결한다. Spring bean이 아니다.
 * 명시 값(Demo): MID `mid_<가맹점 ULID>` ACTIVE, 통화 기록 **없음**(기본 — [detail] `currencies = ""`), 근거 참조 `VAN-REPLY/2026-0001`(기관 회신 — C-36).
 * ADMIN 시험 시계는 실제 시각이라 DATE 모드 날짜는 실행 시점 KST 날짜 기준으로 만든다([tomorrowKst]·[todayKst]).
 */
class MerchantCurrencyApprovalFixture(
    val base: MerchantOpsApprovalFixture,
    private val merchants: MerchantOpsApprovalUseCase,
    private val path: ApprovalPath,
    private val storage: AdminAccountStorage,
) {
    fun install() {
        base.install()
        StubMasterOpsServer.replyWith(StubMasterOpsServer.CURRENCY_ENABLEMENTS) { body ->
            base.executeReplies[base.idOf(body)]?.invoke(body) ?: Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")
        }
    }

    fun uninstall() {
        base.uninstall()
        StubMasterOpsServer.clearDynamic(StubMasterOpsServer.CURRENCY_ENABLEMENTS)
    }

    fun ulid(merchantId: String) = merchantId.removePrefix("mer_")
    fun midOf(merchantId: String) = "mid_" + ulid(merchantId)
    fun currencyOf(merchantId: String) = "cur_" + ulid(merchantId)

    fun todayKst(): LocalDate = LocalDate.now(SEOUL)
    fun tomorrowKst(): LocalDate = todayKst().plusDays(1)

    /** MASTER 상세(B3-2 상태): [accountLifecycle]·[currencies](상세 통화 목록 JSON — 빈 문자열 = 기록 없음)로 무효 조건을 만든다. */
    fun detail(merchantId: String, accountLifecycle: String = "ACTIVE", currencies: String = "") {
        val u = ulid(merchantId)
        var json = StubMasterOpsServer.detailJson(merchantId, currencies = currencies)
        if (accountLifecycle != "ACTIVE") json = json.replace(""""merchantAccountId":"mid_$u","lifecycle":"ACTIVE"""", """"merchantAccountId":"mid_$u","lifecycle":"$accountLifecycle"""")
        StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(merchantId), Reply(200, json))
    }

    /** 이 가맹점 MID의 열린 KRW 한 줄(활성화가 이미 있음). */
    fun openKrw(merchantId: String) = StubMasterOpsServer.currencyJson(currencyOf(merchantId))

    @Suppress("LongParameterList")
    fun request(
        operator: AdminAccount, merchantId: String, mode: String = AT_EXECUTION, fromDate: String? = null, toDate: String? = null, evidence: String = EVIDENCE,
        mid: String = midOf(merchantId), currency: String = "KRW",
    ): ApprovalOutcome = merchants.requestCurrencyEnablement(
        RequestMerchantCurrencyEnablement(
            operator.id.value, storage.load(operator.id).sessionGeneration, merchantId, mid, currency, mode, fromDate, toDate, evidence, path.context(),
        ),
    )

    /** 적격 통화 활성화 요청 — (승인 ID, 가맹점 ID). */
    fun requested(
        operator: AdminAccount = base.operator(), merchantId: String = base.newMerchantId(), mode: String = AT_EXECUTION, fromDate: String? = null, toDate: String? = null,
    ): Pair<String, String> {
        detail(merchantId)
        val outcome = request(operator, merchantId, mode, fromDate, toDate)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture currency request failed: $outcome")).approvalId to merchantId
    }

    companion object {
        const val EVIDENCE = "VAN-REPLY/2026-0001"
        const val AT_EXECUTION = "AT_EXECUTION"
        const val DATE = "DATE"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** KST 날짜 그날 00:00의 UTC `…Z` ms 문자열(시험 독립 계산 — C-7). */
        fun kstMidnight(date: LocalDate): String = date.atStartOfDay(SEOUL).toInstant().toString().let { if (it.length == 20) it.dropLast(1) + ".000Z" else it }
    }
}
