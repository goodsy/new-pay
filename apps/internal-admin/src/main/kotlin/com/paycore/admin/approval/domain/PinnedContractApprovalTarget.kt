package com.paycore.admin.approval.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 계약 승인 요청 시점의 고정 대상(MERCHANT-OPS-002 B2-2 §9.5 pinned — 표시·비교용). MASTER 상세에서 온다: 가맹점·계약·Revision·Scope·효력 기간·승인 여부(요청 시 미승인)·
 * **Revision 등록자**(`registeredBy` — D-MOPS-008 ③, 승인 TX1의 등록자≠승인자 판정 근거). 사업자등록번호·채널 식별값은 없다.
 * [toSnapshotJson]은 결정적 JSON(필드 순서 고정)이며 승인 행 `target_snapshot`(표시용 사본)과 명령 행 `pinned_target`에 같은 값을 넣는다. 가맹점 이름은 자유 문구라 JSON 이스케이프한다
 * (제어 문자는 받지 않는다 — MASTER 응답 모양과 같다).
 * [sameTargetAs]: 승인 TX1의 pinned vs 현재 비교(다르면 INVALIDATED(TARGET_CHANGED)). 표시 이름은 비교하지 않는다(대상 고정값 아님 — C-16).
 */
data class PinnedContractApprovalTarget(
    override val merchantId: String,
    val merchantName: String,
    val contractId: String,
    val revisionNo: Int,
    val scopeKind: String,
    val scopeSiteIds: List<String>,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val registeredBy: String,
    val approved: Boolean = false,
) : PinnedMerchantTarget {
    init {
        require(ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId)) { "merchant id format" }
        require(NAME.matches(merchantName)) { "merchant name (1..200, no control characters)" }
        require(CONTRACT_ID.matches(contractId)) { "contract id format" }
        require(revisionNo >= 1) { "revision number" }
        require(CODE.matches(scopeKind)) { "scope kind" }
        require(scopeSiteIds.all { SITE_ID.matches(it) }) { "scope site id format" }
        require(ACTOR.matches(registeredBy)) { "registeredBy (actor reference)" }
        require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) { "effective period" }
    }

    fun sameTargetAs(current: PinnedContractApprovalTarget): Boolean = copy(merchantName = current.merchantName) == current

    override fun toSnapshotJson(): String =
        "{\"merchantId\":\"$merchantId\",\"merchantName\":${quote(merchantName)},\"contractId\":\"$contractId\",\"revisionNo\":$revisionNo,\"scopeKind\":\"$scopeKind\"," +
            "\"scopeSiteIds\":${scopeSiteIds.joinToString(",", "[", "]") { "\"$it\"" }},\"effectiveFrom\":\"${TIME.format(effectiveFrom)}\"," +
            "\"effectiveTo\":${effectiveTo?.let { "\"${TIME.format(it)}\"" } ?: "null"},\"approved\":$approved,\"registeredBy\":\"$registeredBy\"}"

    companion object {
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        val CONTRACT_ID = Regex("^ctr_$ULID$")
        val SITE_ID = Regex("^site_$ULID$")
        val NAME = Regex("^[^\\p{Cntrl}]{1,200}$")
        private val CODE = Regex("^[A-Z][A-Z_]{0,31}$")

        /** MASTER `ActorRef` 형식(정규 `admin:adm_…`, 시드 `system:…`) — 비교는 문자열 그대로. */
        private val ACTOR = Regex("^[A-Za-z0-9._:@-]{1,64}$")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun quote(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"").let { "\"$it\"" }
    }
}
