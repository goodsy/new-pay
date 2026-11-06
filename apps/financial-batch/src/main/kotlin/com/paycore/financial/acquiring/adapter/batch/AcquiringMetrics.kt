package com.paycore.financial.acquiring.adapter.batch

import com.paycore.financial.acquiring.application.provided.AcquisitionBacklog
import com.paycore.financial.acquiring.application.provided.CloseRunResult
import com.paycore.financial.acquiring.application.provided.ReadAcquisitionBacklogUseCase
import com.paycore.financial.acquiring.application.provided.ResponseRunResult
import com.paycore.financial.acquiring.application.provided.TransmissionResult
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 매입 지표(2-F — Observability, Reconciliation 입력). label에 거래·파일 ID·금액·키가 없다(lane = 매입사 코드/환경, 사유·결과 enum 이름만).
 *
 * Gauge(마지막 [refresh] 값 — 스케줄러 회차마다, 시험은 직접 부른다. 새로 고치기 전에는 NaN):
 * - `paycore.financial.acquiring.files.{transmission_unknown, transmission_quarantined, upload_failed_repeated, receipt_conflict}`
 * - `paycore.financial.acquiring.records.{unknown, conflict, awaiting_result}`, `paycore.financial.acquiring.responses.quarantined`,
 *   `paycore.financial.acquiring.integrity.netting_mismatch`(0이어야 한다)
 * - `paycore.financial.acquiring.waiting{lane,kind,reason}` = 마지막 봉인의 대기 건수, `paycore.financial.acquiring.waiting.oldest_age_seconds{lane,kind,reason}` = 가장 오래된
 *   대기 사건(승인·취소 시각)부터 지금까지(체류), `paycore.financial.acquiring.cancel.reconcile{lane}` = 대사로 넘긴 전송 후 취소
 *
 * Counter(스케줄러 회차 결과 누적): `paycore.financial.acquiring.transmission{outcome,detail}`(UPLOAD_FAILED 반복 = 임시 파일 상한 거절 의심),
 * `paycore.financial.acquiring.responses{result}`, `paycore.financial.acquiring.records.results{result}`, `paycore.financial.acquiring.seal{outcome}`.
 * 경보 임계값은 운영값(LIVE Gate §14-12) — 지표는 판단 근거만 낸다.
 */
@Component
class AcquiringMetrics(private val registry: MeterRegistry, private val backlog: ReadAcquisitionBacklogUseCase) {

    private val snapshot = AtomicReference<AcquisitionBacklog?>(null)
    private val waiting = ConcurrentHashMap<List<String>, WaitingValue>()
    private val reconcile = ConcurrentHashMap<String, Double>()

    /** [ageSeconds] = 새로 고침 때 매입 시계(AcquiringClock — backlog.observedAt)로 계산한 체류 초(리뷰 n-6 — 시스템 시계를 쓰지 않는다). */
    private class WaitingValue(@Volatile var count: Double, @Volatile var ageSeconds: Double)

    init {
        fileGauge("files.transmission_unknown") { it.transmissionUnknown }
        fileGauge("files.transmission_quarantined") { it.transmissionQuarantined }
        fileGauge("files.upload_failed_repeated") { it.uploadFailedRepeated }
        fileGauge("files.receipt_conflict") { it.receiptConflicts }
        fileGauge("records.unknown") { it.recordsUnknown }
        fileGauge("records.conflict") { it.recordsConflict }
        fileGauge("records.awaiting_result") { it.awaitingResults }
        fileGauge("responses.quarantined") { it.responsesQuarantined }
        fileGauge("integrity.netting_mismatch") { it.nettingMismatches }
        fileGauge("cancel.not_required_original_conflict") { it.cancelNotRequiredOriginalConflict }
    }

    /** DB count와 대기 현황을 다시 읽는다(읽기 TX 하나). 사라진 대기 묶음은 0으로 둔다(gauge는 남는다). */
    fun refresh(): AcquisitionBacklog {
        val b = backlog.backlog()
        snapshot.set(b)
        val seen = mutableSetOf<List<String>>()
        b.waiting.forEach { w ->
            val key = listOf(w.lane, w.kind, w.reason)
            seen += key
            val value = waiting.computeIfAbsent(key) { k ->
                WaitingValue(0.0, 0.0).also { v ->
                    val tags = listOf("lane", k[0], "kind", k[1], "reason", k[2])
                    Gauge.builder("$PREFIX.waiting", v) { it.count }.tags(*tags.toTypedArray()).register(registry)
                    Gauge.builder("$PREFIX.waiting.oldest_age_seconds", v) { it.ageSeconds }.tags(*tags.toTypedArray()).register(registry)
                }
            }
            value.count = w.count.toDouble()
            value.ageSeconds = Duration.between(w.oldestEventAt, b.observedAt).seconds.coerceAtLeast(0).toDouble()
        }
        waiting.filterKeys { it !in seen }.values.forEach {
            it.count = 0.0
            it.ageSeconds = 0.0
        }
        b.cancelReconcile.forEach { (lane, n) ->
            if (reconcile.put(lane, n.toDouble()) == null) {
                Gauge.builder("$PREFIX.cancel.reconcile", reconcile) { it[lane] ?: 0.0 }.tag("lane", lane).register(registry)
            }
        }
        return b
    }

    fun countSeal(outcome: String) = registry.counter("$PREFIX.seal", "outcome", outcome).increment()

    fun countTransmission(results: List<TransmissionResult>) = results.forEach { r ->
        val detail = r.detail?.takeIf { DETAIL.matches(it) } ?: "NONE"
        registry.counter("$PREFIX.transmission", "outcome", r.outcome.name, "detail", detail).increment()
    }

    fun countResponses(r: ResponseRunResult) {
        responseCounter("applied", r.applied)
        responseCounter("quarantined", r.quarantined)
        responseCounter("deferred", r.deferred)
        responseCounter("already_processed", r.alreadyProcessed)
        responseCounter("error", r.errors)
        if (r.outcome != ResponseRunResult.Outcome.COMPLETED) responseCounter(r.outcome.name.lowercase(), 1)
        recordCounter("decided", r.recordsDecided)
        recordCounter("duplicate", r.duplicates)
        recordCounter("conflict", r.conflicts)
    }

    fun countClose(r: CloseRunResult) {
        recordCounter("unknown", r.unknownRecords)
        if (r.outcome != CloseRunResult.Outcome.COMPLETED) registry.counter("$PREFIX.close", "outcome", r.outcome.name).increment()
    }

    private fun responseCounter(result: String, n: Int) {
        if (n > 0) registry.counter("$PREFIX.responses", "result", result).increment(n.toDouble())
    }

    private fun recordCounter(result: String, n: Int) {
        if (n > 0) registry.counter("$PREFIX.records.results", "result", result).increment(n.toDouble())
    }

    private fun fileGauge(name: String, value: (AcquisitionBacklog) -> Long) {
        Gauge.builder("$PREFIX.$name", snapshot) { s -> s.get()?.let { value(it).toDouble() } ?: Double.NaN }.register(registry)
    }

    private companion object {
        const val PREFIX = "paycore.financial.acquiring"
        val DETAIL = Regex("^[A-Z_]{1,40}$")
    }
}
