package com.paycore.admin.paymentops.adapter.integration

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 운영 홈 KPI 응답이 **이 요청의 답**인가(ADMIN-HOME-KPI-001 §4.3 — [PaymentResponseShape.HOME_KPI] 모양 검사를 통과한 문서에만, 아니면 해석 불가 → fail-closed 503):
 * `businessDate` = asOf의 Asia/Seoul 날짜, `from` = 그 날 00:00 Asia/Seoul의 순간, from ≤ asOf, asOf = from이면 빈 items, (environment, currency) 중복 없음, 정렬(LIVE → TEST,
 * currency 오름차순), 행마다 net = approved − cancelled(BigInteger), approved = 0 ⇒ approvedCount = 0, approved > 0 또는 cancelled > 0(사실 없는 행 없음).
 * PAYMENT 정의를 다시 계산하지 않는다(값의 업무 정확성은 PAYMENT 몫) — 화면에 내보낼 수 있는 일관된 문서인지만 본다.
 */
internal object HomeKpiAnswer {
    private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    private val ORDER: Comparator<Pair<String, String>> = compareBy({ if (it.first == "LIVE") 0 else 1 }, { it.first }, { it.second })

    @Suppress("UNCHECKED_CAST", "ReturnCount")
    fun belongs(doc: Map<String, Any?>): Boolean {
        val asOf = Instant.parse(doc["asOf"] as String)
        val date = LocalDate.parse(doc["businessDate"] as String)
        val from = Instant.parse(doc["from"] as String)
        if (LocalDate.ofInstant(asOf, ZONE) != date || date.atStartOfDay(ZONE).toInstant() != from || from > asOf) return false
        val items = doc["items"] as List<Map<String, Any?>>
        if (asOf == from && items.isNotEmpty()) return false
        val keys = items.map { (it["environment"] as String) to (it["currency"] as String) }
        if (keys.toSet().size != keys.size || keys != keys.sortedWith(ORDER)) return false
        return items.all { i ->
            val approved = BigInteger(i["approvedAmountMinor"] as String)
            val cancelled = BigInteger(i["cancelledAmountMinor"] as String)
            val net = BigInteger(i["netApprovedAmountMinor"] as String)
            val count = i["approvedCount"] as Long
            net == approved.subtract(cancelled) && (approved.signum() > 0 || count == 0L) && (approved.signum() > 0 || cancelled.signum() > 0)
        }
    }
}
