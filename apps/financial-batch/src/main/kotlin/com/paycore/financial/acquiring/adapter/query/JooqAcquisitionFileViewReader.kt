package com.paycore.financial.acquiring.adapter.query

import com.paycore.financial.acquiring.application.provided.AcquisitionAttemptView
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSummary
import com.paycore.financial.acquiring.application.provided.AcquisitionHistory
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordResultView
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordStatusCounts
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordView
import com.paycore.financial.acquiring.application.provided.AcquisitionResponseRef
import com.paycore.financial.acquiring.application.provided.AcquisitionResponseView
import com.paycore.financial.acquiring.application.required.AcquisitionFileDetailRows
import com.paycore.financial.acquiring.application.required.AcquisitionFileViewReader
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_ACCEPTED_RECORD
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_FILE
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_FILE_RECORD
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_REACQUISITION_NOT_REQUIRED
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_RECORD_RESULT
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_RESPONSE
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_TRANSMISSION_ATTEMPT
import com.paycore.financial.jooq.generated.Tables.ACQUISITION_TRANSMISSION_OUTCOME
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectField
import org.jooq.SelectFromStep
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 매입 현황 관리 조회(ACQ-VIEW-001 §3.3·§4 — jOOQ CQRS 조회 측, **쓰기·잠금 없음**, 비권위). 모든 SELECT에 질의 시간 상한 hint([AcquisitionReadLimits] — MySQL
 * `MAX_EXECUTION_TIME`), 각 메서드는 **한 읽기 전용 REPEATABLE READ TX**(InnoDB 일관 읽기 스냅샷은 첫 읽기에 고정 — 찢어진 상세·목록 없음).
 *
 * - 목록: `ORDER BY business_date DESC, file_id DESC` keyset(`business_date < d OR (business_date = d AND file_id < f)`). 색인을 고정한다(FORCE INDEX — 관리 조회가 계획에 따라
 *   전체 스캔으로 바뀌지 않게): 매입사 없음 → `ix_acquisition_file_business_date`(V16), 매입사 있음 → `uk_acquisition_file_lane_date_version`(환경이 없으면 두 값의 IN). 그 쪽
 *   파일들의 레코드 현재 상태별 건수·격리된 응답 수는 같은 TX의 두 번째·세 번째 문장(파일 ID IN 목록).
 * - "확인 필요만"(needsAttention): Use Case `AcquisitionFileViewService.attentionOf`와 같은 정의의 SQL 조건(시험이 둘을 대조).
 * - 레코드 현재 상태: 결과 행에서 계산 — CONFLICT 행 있음 → CONFLICT, 아니면 entry_no 순 첫 ACCEPTED/REJECTED 행(출처 FILE_REJECTED면 FILE_REJECTED), 결정 없이 UNKNOWN 행 →
 *   UNKNOWN, 행 없음 → AWAITING(`RecordResultRule.status`와 같은 정의 — 시험이 레코드마다 대조).
 * - 날짜는 `DATE_FORMAT('%Y-%m-%d')`, 시각은 `DATE_FORMAT(…'%fZ')` 문자열로 읽고(TZ-FIX-001 — JVM·driver 시간대 변환 없음) 비교 값은 `CAST('…' AS DATE/DATETIME(3))` 리터럴.
 * - **읽지 않는 열**(§4.5·D-ACQV-002): `acquisition_file_content`·`acquisition_response.content`(바이트)·`acquisition_response.file_name`·`acquisition_file.sender_id`, 레코드
 *   `approval_number`·`provider_transaction_id`·`original_provider_transaction_id`·`issuer_code`·`card_type`·`plan_kind`·`installment_months` — SELECT 목록에 없다.
 */
@Component
class JooqAcquisitionFileViewReader(private val dsl: DSLContext) : AcquisitionFileViewReader {

    private val f = ACQUISITION_FILE

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun search(criteria: AcquisitionFileSearchCriteria, overdueBefore: Instant?, rows: Int): List<AcquisitionFileSummary> = AcquisitionReadLimits.guard {
        val table = when {
            criteria.fileId != null -> f
            criteria.acquirerCode != null -> f.forceIndex(LANE_INDEX)
            else -> f.forceIndex(PERIOD_INDEX)
        }
        val heads = select(*headFields())
            .from(table)
            .where(conditions(criteria, overdueBefore))
            .orderBy(f.BUSINESS_DATE.desc(), f.FILE_ID.desc())
            .limit(rows)
            .fetch()
        val ids = heads.map { it.get(f.FILE_ID) }
        val counts = statusCounts(ids)
        val quarantined = quarantinedResponses(ids)
        val stages = reacquisitionStages(ids)
        heads.map { summary(it, counts[it.get(f.FILE_ID)] ?: AcquisitionRecordStatusCounts(), quarantined[it.get(f.FILE_ID)] ?: 0, stages[it.get(f.FILE_ID)] ?: NO_STAGES) }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun detail(fileId: String, records: AcquisitionRecordSearchCriteria, recordRows: Int): AcquisitionFileDetailRows? = AcquisitionReadLimits.guard {
        val cutoffAt = iso(f.CUTOFF_AT).`as`("cutoff_at_utc")
        val leaseUntil = iso(f.TRANSMISSION_LEASE_UNTIL).`as`("lease_until_utc")
        val updatedAt = iso(f.UPDATED_AT).`as`("updated_at_utc")
        // 첫 문장(스냅샷 고정). 별칭 view_file_head는 시험 probe의 표지다(값 의미 없음).
        val head = select(*headFields(), f.CUTOFF_POLICY_VERSION, cutoffAt, f.CONTENT_SHA256, f.CONTENT_BYTES, leaseUntil, updatedAt, DSL.inline(1).`as`("view_file_head"))
            .from(f)
            .where(f.FILE_ID.eq(fileId))
            .fetchOne() ?: return@guard null
        val ids = listOf(fileId)
        AcquisitionFileDetailRows(
            summary = summary(
                head, statusCounts(ids)[fileId] ?: AcquisitionRecordStatusCounts(), quarantinedResponses(ids)[fileId] ?: 0, reacquisitionStages(ids)[fileId] ?: NO_STAGES,
            ),
            cutoffPolicyVersion = head.get(f.CUTOFF_POLICY_VERSION),
            cutoffAt = Instant.parse(head.get(cutoffAt)),
            contentSha256 = head.get(f.CONTENT_SHA256),
            contentBytes = head.get(f.CONTENT_BYTES),
            transmissionLeaseUntil = head.get(leaseUntil)?.let(Instant::parse),
            updatedAt = Instant.parse(head.get(updatedAt)),
            attempts = attempts(fileId),
            responses = responses(fileId),
            records = recordPage(fileId, records, recordRows),
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria, rows: Int): List<AcquisitionRecordView>? = AcquisitionReadLimits.guard {
        select(f.FILE_ID).from(f).where(f.FILE_ID.eq(fileId)).fetchOne() ?: return@guard null
        recordPage(fileId, criteria, rows)
    }

    // ---- 목록 조건 ----

    private fun conditions(c: AcquisitionFileSearchCriteria, overdueBefore: Instant?): List<Condition> {
        val where = mutableListOf<Condition>()
        c.fileId?.let { where += f.FILE_ID.eq(it) }
        if (c.acquirerCode != null) {
            where += f.ACQUIRER_CODE.eq(c.acquirerCode)
            // 레인 UNIQUE 색인 앞부분(acquirer_code, environment)을 범위로 쓴다 — 환경이 없으면 두 값의 IN.
            where += f.ENVIRONMENT.`in`(c.environment?.let(::listOf) ?: ENVIRONMENTS)
        } else {
            c.environment?.let { where += f.ENVIRONMENT.eq(it) }
        }
        c.transmissionState?.let { where += f.TRANSMISSION_STATE.eq(it) }
        c.receiptState?.let { where += f.RECEIPT_STATE.eq(it) }
        c.resultState?.let { where += f.RESULT_STATE.eq(it) }
        c.businessDateFrom?.let { where += f.BUSINESS_DATE.ge(date(it)) }
        c.businessDateTo?.let { where += f.BUSINESS_DATE.le(date(it)) }
        c.after?.let { cursor ->
            val d = date(cursor.businessDate)
            where += f.BUSINESS_DATE.lt(d).or(f.BUSINESS_DATE.eq(d).and(f.FILE_ID.lt(cursor.fileId)))
        }
        if (c.needsAttention) where += needsAttention(overdueBefore)
        return where
    }

    /** `AcquisitionFileViewService.attentionOf`와 같은 정의(§4.4). */
    private fun needsAttention(overdueBefore: Instant?): Condition {
        val response = ACQUISITION_RESPONSE.`as`("na_response")
        val conflict = ACQUISITION_RECORD_RESULT.`as`("na_conflict")
        val unknown = ACQUISITION_RECORD_RESULT.`as`("na_unknown")
        val decided = ACQUISITION_RECORD_RESULT.`as`("na_decided")
        val overdue = overdueBefore?.let {
            f.RESULT_STATE.eq(AWAITING).and(f.TRANSMISSION_STATE.eq(TRANSMITTED)).and(f.TRANSMITTED_AT.le(utc(it)))
        } ?: DSL.falseCondition()
        return DSL.or(
            f.TRANSMISSION_STATE.`in`(TRANSMISSION_UNKNOWN, TRANSMISSION_QUARANTINED, TRANSMISSION_WITHHELD),
            f.RECEIPT_STATE.eq(RECEIPT_CONFLICT),
            f.RESULT_STATE.eq(DEADLINE_PASSED),
            overdue,
            DSL.exists(DSL.selectOne().from(response).where(response.FILE_ID.eq(f.FILE_ID), response.DISPOSITION.eq(QUARANTINED))),
            DSL.exists(DSL.selectOne().from(conflict).where(conflict.FILE_ID.eq(f.FILE_ID), conflict.RESULT.eq(CONFLICT))),
            DSL.exists(
                DSL.selectOne().from(unknown).where(
                    unknown.FILE_ID.eq(f.FILE_ID), unknown.RESULT.eq(UNKNOWN),
                    DSL.notExists(
                        DSL.selectOne().from(decided)
                            .where(decided.FILE_ID.eq(unknown.FILE_ID), decided.SEQ.eq(unknown.SEQ), decided.RESULT.`in`(ACCEPTED, REJECTED, CONFLICT)),
                    ),
                ),
            ),
        )
    }

    // ---- 파일 요약 ----

    private val businessDate = DSL.field("DATE_FORMAT({0}, '%Y-%m-%d')", String::class.java, f.BUSINESS_DATE).`as`("business_date_text")
    private val sealedAt = iso(f.SEALED_AT).`as`("sealed_at_utc")
    private val transmittedAt = iso(f.TRANSMITTED_AT).`as`("transmitted_at_utc")

    private fun headFields(): Array<SelectField<*>> = arrayOf(
        f.FILE_ID, f.ACQUIRER_CODE, f.ENVIRONMENT, businessDate, f.FILE_VERSION, f.ORIGINAL_FILE_ID, f.RECORD_COUNT, f.SALE_COUNT, f.SALE_AMOUNT_MINOR,
        f.CANCEL_COUNT, f.CANCEL_AMOUNT_MINOR, f.NET_AMOUNT_MINOR, f.TRANSMISSION_STATE, f.RECEIPT_STATE, f.RESULT_STATE, sealedAt, transmittedAt,
    )

    private fun summary(r: Record, counts: AcquisitionRecordStatusCounts, quarantined: Int, stages: Pair<Int, Int>) = AcquisitionFileSummary(
        fileId = r.get(f.FILE_ID), acquirerCode = r.get(f.ACQUIRER_CODE), environment = r.get(f.ENVIRONMENT), businessDate = LocalDate.parse(r.get(businessDate)),
        fileVersion = r.get(f.FILE_VERSION), originalFileId = r.get(f.ORIGINAL_FILE_ID), recordCount = r.get(f.RECORD_COUNT), saleCount = r.get(f.SALE_COUNT),
        saleAmountMinor = r.get(f.SALE_AMOUNT_MINOR), cancelCount = r.get(f.CANCEL_COUNT), cancelAmountMinor = r.get(f.CANCEL_AMOUNT_MINOR),
        netAmountMinor = r.get(f.NET_AMOUNT_MINOR), transmissionState = r.get(f.TRANSMISSION_STATE), receiptState = r.get(f.RECEIPT_STATE),
        resultState = r.get(f.RESULT_STATE), sealedAt = Instant.parse(r.get(sealedAt)), transmittedAt = r.get(transmittedAt)?.let(Instant::parse),
        recordStatusCounts = counts, quarantinedResponses = quarantined, reacquisitionCandidates = stages.first, reacquisitionInProgress = stages.second,
    )

    /** 파일별 레코드 현재 상태 건수(한 문장 — 파일 ID IN 목록). */
    private fun statusCounts(fileIds: List<String>): Map<String, AcquisitionRecordStatusCounts> {
        if (fileIds.isEmpty()) return emptyMap()
        val r = ACQUISITION_FILE_RECORD
        val status = recordStatus(r.FILE_ID, r.SEQ).`as`("record_status")
        val inner = DSL.select(r.FILE_ID, status).from(r).where(r.FILE_ID.`in`(fileIds)).asTable("view_record_status")
        val fileId = DSL.field(DSL.name(inner.name, r.FILE_ID.name), String::class.java)
        val value = DSL.field(DSL.name(inner.name, status.name), String::class.java)
        val n = DSL.count().`as`("record_count_by_status")
        return select(fileId, value, n).from(inner).groupBy(fileId, value).fetch()
            .groupBy({ it.get(fileId) }, { it.get(value) to it.get(n) })
            .mapValues { (_, byStatus) ->
                val m = byStatus.toMap()
                AcquisitionRecordStatusCounts(
                    awaiting = m[AWAITING] ?: 0, accepted = m[ACCEPTED] ?: 0, rejected = m[REJECTED] ?: 0, fileRejected = m[FILE_REJECTED] ?: 0,
                    unknown = m[UNKNOWN] ?: 0, conflict = m[CONFLICT] ?: 0,
                )
            }
    }

    /**
     * (ACQ-ADMIN-001 B2 — §5.1 L-9, AC9) 파일별 재매입 단계 건수(CANDIDATE, IN_PROGRESS) — `ReacquisitionBadgeRule.recordStage`와 같은 정의(시험이 레코드마다 대조):
     * 현재 상태 REJECTED·FILE_REJECTED이고, 그 거래·종류에 매입 확정 표지·"재매입 불요" 종결이 없는 레코드 중 더 높은 세대 레코드가 있으면 IN_PROGRESS, 없으면 CANDIDATE.
     * 한 문장(파일 ID IN 목록), 상관 부질의는 PK·UNIQUE(거래, 종류, 세대) 색인으로 찾는다.
     */
    private fun reacquisitionStages(fileIds: List<String>): Map<String, Pair<Int, Int>> {
        if (fileIds.isEmpty()) return emptyMap()
        val r = ACQUISITION_FILE_RECORD
        val a = ACQUISITION_ACCEPTED_RECORD.`as`("rq_accepted")
        val x = ACQUISITION_REACQUISITION_NOT_REQUIRED.`as`("rq_not_required")
        val h = ACQUISITION_FILE_RECORD.`as`("rq_higher")
        val unresolved = DSL.notExists(DSL.selectOne().from(a).where(a.TRANSACTION_ID.eq(r.TRANSACTION_ID), a.RECORD_TYPE.eq(r.RECORD_TYPE)))
            .and(DSL.notExists(DSL.selectOne().from(x).where(x.TRANSACTION_ID.eq(r.TRANSACTION_ID), x.RECORD_TYPE.eq(r.RECORD_TYPE))))
        val higher = DSL.exists(
            DSL.selectOne().from(h).where(h.TRANSACTION_ID.eq(r.TRANSACTION_ID), h.RECORD_TYPE.eq(r.RECORD_TYPE), h.GENERATION.gt(r.GENERATION)),
        )
        val stage = DSL.`when`(higher, DSL.inline(IN_PROGRESS_STAGE)).otherwise(DSL.inline(CANDIDATE_STAGE)).`as`("reacquisition_stage")
        val inner = DSL.select(r.FILE_ID, stage).from(r)
            .where(r.FILE_ID.`in`(fileIds), recordStatus(r.FILE_ID, r.SEQ).`in`(REJECTED, FILE_REJECTED), unresolved)
            .asTable("view_reacquisition_stage")
        val fileId = DSL.field(DSL.name(inner.name, r.FILE_ID.name), String::class.java)
        val value = DSL.field(DSL.name(inner.name, stage.name), String::class.java)
        val n = DSL.count().`as`("record_count_by_stage")
        return select(fileId, value, n).from(inner).groupBy(fileId, value).fetch()
            .groupBy({ it.get(fileId) }, { it.get(value) to it.get(n) })
            .mapValues { (_, byStage) -> byStage.toMap().let { (it[CANDIDATE_STAGE] ?: 0) to (it[IN_PROGRESS_STAGE] ?: 0) } }
    }

    private fun quarantinedResponses(fileIds: List<String>): Map<String, Int> {
        if (fileIds.isEmpty()) return emptyMap()
        val p = ACQUISITION_RESPONSE
        val n = DSL.count().`as`("quarantined_responses")
        return select(p.FILE_ID, n).from(p).where(p.FILE_ID.`in`(fileIds), p.DISPOSITION.eq(QUARANTINED)).groupBy(p.FILE_ID).fetch()
            .associate { it.get(p.FILE_ID) to it.get(n) }
    }

    /**
     * 레코드 현재 상태 식(`RecordResultRule.status`와 같은 정의). 상관 부질의만 쓰고 결과 행은 PK(file_id, seq, entry_no)로 찾는다.
     */
    private fun recordStatus(fileId: Field<String>, seq: Field<Int>): Field<String> {
        val c = ACQUISITION_RECORD_RESULT.`as`("rs_conflict")
        val d = ACQUISITION_RECORD_RESULT.`as`("rs_decided")
        val u = ACQUISITION_RECORD_RESULT.`as`("rs_unknown")
        val firstDecided: Field<String> = DSL.field(
            DSL.select(DSL.`when`(d.RESULT.eq(ACCEPTED), DSL.inline(ACCEPTED)).`when`(d.SOURCE.eq(FILE_REJECTED_SOURCE), DSL.inline(FILE_REJECTED)).otherwise(DSL.inline(REJECTED)))
                .from(d).where(d.FILE_ID.eq(fileId), d.SEQ.eq(seq), d.RESULT.`in`(ACCEPTED, REJECTED)).orderBy(d.ENTRY_NO).limit(1),
        )
        return DSL.`when`(DSL.exists(DSL.selectOne().from(c).where(c.FILE_ID.eq(fileId), c.SEQ.eq(seq), c.RESULT.eq(CONFLICT))), DSL.inline(CONFLICT))
            .otherwise(
                DSL.coalesce(
                    firstDecided,
                    DSL.`when`(DSL.exists(DSL.selectOne().from(u).where(u.FILE_ID.eq(fileId), u.SEQ.eq(seq), u.RESULT.eq(UNKNOWN))), DSL.inline(UNKNOWN))
                        .otherwise(DSL.inline(AWAITING)),
                ),
            )
    }

    // ---- 상세 하위 ----

    private fun attempts(fileId: String): AcquisitionHistory<AcquisitionAttemptView> {
        val a = ACQUISITION_TRANSMISSION_ATTEMPT
        val o = ACQUISITION_TRANSMISSION_OUTCOME
        val total = select(DSL.count()).from(a).where(a.FILE_ID.eq(fileId)).fetchOne(0, Int::class.java) ?: 0
        val claimedAt = iso(a.CLAIMED_AT).`as`("claimed_at_utc")
        val leaseUntil = iso(a.LEASE_UNTIL).`as`("attempt_lease_until_utc")
        val decidedAt = iso(o.DECIDED_AT).`as`("decided_at_utc")
        val latest = select(a.ATTEMPT_NO, a.RESUME_STATE, claimedAt, leaseUntil, o.OUTCOME, o.OBSERVED, o.EVIDENCE, o.REASON, o.RESULTING_STATE, decidedAt)
            .from(a).leftJoin(o).on(o.FILE_ID.eq(a.FILE_ID), o.ATTEMPT_NO.eq(a.ATTEMPT_NO))
            .where(a.FILE_ID.eq(fileId)).orderBy(a.ATTEMPT_NO.desc()).limit(AcquisitionHistory.LATEST)
            .fetch { r ->
                AcquisitionAttemptView(
                    attemptNo = r.get(a.ATTEMPT_NO), resumeState = r.get(a.RESUME_STATE), claimedAt = Instant.parse(r.get(claimedAt)),
                    leaseUntil = Instant.parse(r.get(leaseUntil)), outcome = r.get(o.OUTCOME), observed = r.get(o.OBSERVED), evidence = r.get(o.EVIDENCE),
                    reason = r.get(o.REASON), resultingState = r.get(o.RESULTING_STATE), decidedAt = r.get(decidedAt)?.let(Instant::parse),
                )
            }
        return AcquisitionHistory(total, latest)
    }

    private fun responses(fileId: String): AcquisitionHistory<AcquisitionResponseView> {
        val p = ACQUISITION_RESPONSE
        val total = select(DSL.count()).from(p).where(p.FILE_ID.eq(fileId)).fetchOne(0, Int::class.java) ?: 0
        val institutionAt = iso(p.INSTITUTION_AT).`as`("institution_at_utc")
        val observedAt = iso(p.OBSERVED_AT).`as`("observed_at_utc")
        val latest = select(p.KIND, p.SEQUENCE_NO, p.DISPOSITION, p.QUARANTINE_REASON, p.ACK_OUTCOME, p.ACK_REASON_CODE, p.EVIDENCE, institutionAt, observedAt)
            .from(p).where(p.FILE_ID.eq(fileId)).orderBy(p.ID.desc()).limit(AcquisitionHistory.LATEST)
            .fetch { r ->
                AcquisitionResponseView(
                    kind = r.get(p.KIND), sequenceNo = r.get(p.SEQUENCE_NO), disposition = r.get(p.DISPOSITION), quarantineReason = r.get(p.QUARANTINE_REASON),
                    ackOutcome = r.get(p.ACK_OUTCOME), ackReasonCode = r.get(p.ACK_REASON_CODE), evidence = r.get(p.EVIDENCE),
                    institutionAt = r.get(institutionAt)?.let(Instant::parse), observedAt = Instant.parse(r.get(observedAt)),
                )
            }
        return AcquisitionHistory(total, latest)
    }

    /** 레코드 쪽(seq 오름차순, 상태 필터) + 그 레코드들의 결과 행(한 문장). D-ACQV-002 열은 SELECT하지 않는다. */
    private fun recordPage(fileId: String, criteria: AcquisitionRecordSearchCriteria, rows: Int): List<AcquisitionRecordView> {
        val r = ACQUISITION_FILE_RECORD
        val status = recordStatus(r.FILE_ID, r.SEQ).`as`("record_status")
        val institutionDate = DSL.field("DATE_FORMAT({0}, '%Y-%m-%d')", String::class.java, r.INSTITUTION_TRANSACTION_DATE).`as`("institution_date_text")
        val eventAt = iso(r.EVENT_AT).`as`("event_at_utc")
        val where = mutableListOf(r.FILE_ID.eq(fileId), r.SEQ.gt(criteria.afterSeq))
        criteria.status?.let { where += recordStatus(r.FILE_ID, r.SEQ).eq(it) }
        val heads = select(
            r.SEQ, r.RECORD_TYPE, r.TRANSACTION_ID, r.ORIGINAL_TRANSACTION_ID, r.MERCHANT_REF, institutionDate, eventAt, r.CURRENCY, r.APPROVED_AMOUNT_MINOR,
            r.NETTED_CANCEL_AMOUNT_MINOR, r.RECORD_AMOUNT_MINOR, r.GENERATION, r.REACQUISITION_OF, status,
        ).from(r).where(where).orderBy(r.SEQ).limit(rows).fetch()
        val results = resultsOf(fileId, heads.map { it.get(r.SEQ) })
        return heads.map { h ->
            AcquisitionRecordView(
                seq = h.get(r.SEQ), recordType = h.get(r.RECORD_TYPE), transactionId = h.get(r.TRANSACTION_ID), originalTransactionId = h.get(r.ORIGINAL_TRANSACTION_ID),
                merchantAccountId = h.get(r.MERCHANT_REF), institutionTransactionDate = LocalDate.parse(h.get(institutionDate)), eventAt = Instant.parse(h.get(eventAt)),
                currency = h.get(r.CURRENCY), approvedAmountMinor = h.get(r.APPROVED_AMOUNT_MINOR), nettedCancelAmountMinor = h.get(r.NETTED_CANCEL_AMOUNT_MINOR),
                recordAmountMinor = h.get(r.RECORD_AMOUNT_MINOR), generation = h.get(r.GENERATION), reacquisitionOf = h.get(r.REACQUISITION_OF),
                status = h.get(status), results = results[h.get(r.SEQ)].orEmpty(),
            )
        }
    }

    private fun resultsOf(fileId: String, seqs: List<Int>): Map<Int, List<AcquisitionRecordResultView>> {
        if (seqs.isEmpty()) return emptyMap()
        val x = ACQUISITION_RECORD_RESULT
        val p = ACQUISITION_RESPONSE
        val recognizedAt = iso(x.RECOGNIZED_AT).`as`("recognized_at_utc")
        return select(x.SEQ, x.ENTRY_NO, x.RESULT, x.OBSERVED_RESULT, x.REASON_CODE, x.SOURCE, p.KIND, p.SEQUENCE_NO, recognizedAt)
            .from(x).leftJoin(p).on(p.ID.eq(x.RESPONSE_ID))
            .where(x.FILE_ID.eq(fileId), x.SEQ.`in`(seqs)).orderBy(x.SEQ, x.ENTRY_NO)
            .fetch()
            .groupBy({ it.get(x.SEQ) }) { e ->
                AcquisitionRecordResultView(
                    entryNo = e.get(x.ENTRY_NO), result = e.get(x.RESULT), observedResult = e.get(x.OBSERVED_RESULT), reasonCode = e.get(x.REASON_CODE),
                    source = e.get(x.SOURCE), responseRef = e.get(p.KIND)?.let { kind -> AcquisitionResponseRef(kind, e.get(p.SEQUENCE_NO)) },
                    recognizedAt = Instant.parse(e.get(recognizedAt)),
                )
            }
    }

    // ---- 공통 ----

    /** 모든 SELECT의 시작: 질의 시간 상한 hint(§3.2). */
    private fun select(vararg fields: SelectField<*>): SelectFromStep<Record> = dsl.select(*fields).hint(AcquisitionReadLimits.HINT)

    private fun iso(field: Field<*>): Field<String> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field)

    private fun date(value: LocalDate): Field<LocalDate> = DSL.field("CAST({0} AS DATE)", LocalDate::class.java, DSL.inline(value.toString()))

    private fun utc(at: Instant): Field<java.time.LocalDateTime> =
        DSL.field("CAST({0} AS DATETIME(3))", java.time.LocalDateTime::class.java, DSL.inline(LITERAL.format(at)))

    private companion object {
        const val PERIOD_INDEX = "ix_acquisition_file_business_date"
        const val LANE_INDEX = "uk_acquisition_file_lane_date_version"
        val ENVIRONMENTS = listOf("TEST", "LIVE")
        val LITERAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

        // 저장 값(V5·V7·V10 CHECK).
        const val TRANSMITTED = "TRANSMITTED"
        const val TRANSMISSION_UNKNOWN = "TRANSMISSION_UNKNOWN"
        const val TRANSMISSION_QUARANTINED = "TRANSMISSION_QUARANTINED"
        const val TRANSMISSION_WITHHELD = "TRANSMISSION_WITHHELD"
        const val RECEIPT_CONFLICT = "RECEIPT_CONFLICT"
        const val DEADLINE_PASSED = "DEADLINE_PASSED"
        const val QUARANTINED = "QUARANTINED"
        const val AWAITING = "AWAITING"
        const val ACCEPTED = "ACCEPTED"
        const val REJECTED = "REJECTED"
        const val FILE_REJECTED = "FILE_REJECTED"
        const val CANDIDATE_STAGE = "CANDIDATE"
        const val IN_PROGRESS_STAGE = "IN_PROGRESS"
        val NO_STAGES = 0 to 0
        const val FILE_REJECTED_SOURCE = "FILE_REJECTED"
        const val UNKNOWN = "UNKNOWN"
        const val CONFLICT = "CONFLICT"
    }
}
