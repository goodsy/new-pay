package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.OPERATOR_APPROVAL_DECISION
import com.paycore.payment.jooq.generated.Tables.PAYMENT_CANCELLATION
import com.paycore.payment.payment.application.required.LegacyOperatorCancellationRow
import com.paycore.payment.payment.application.required.OperatorApprovalDecision
import com.paycore.payment.payment.application.required.OperatorDecisionKind
import com.paycore.payment.payment.application.required.OperatorDecisionOrigin
import com.paycore.payment.payment.application.required.OperatorDecisionPage
import com.paycore.payment.payment.application.required.OperatorDecisionReader
import com.paycore.payment.payment.application.required.OperatorDecisionRow
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.QueryTimedOut
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 운영자 결정 조회(ADMIN-PAYMENT-OPS-001 §4.3·§4.17 — jOOQ CQRS, 쓰기 없음, 비권위). 결정 조회는 결정 행 + 현재 취소 상태, 옛 행 호환은 결정 행 없는 `payment_cancellation.approval_id`
 * (I-29), 목록은 keyset `seq > ?` 오름차순 + 같은 읽기 TX의 DB 시각(`NOW(3)`). 모든 SELECT에 질의 시간 상한 hint를 붙인다(§4.7).
 */
@Component
class JooqOperatorDecisionReader(private val dsl: DSLContext) : OperatorDecisionReader {

    @Transactional(readOnly = true)
    override fun find(approvalId: OperatorApprovalId): OperatorDecisionRow? = AdminReadLimits.guard {
        val d = OPERATOR_APPROVAL_DECISION
        val c = PAYMENT_CANCELLATION
        select(c.STATUS).from(d).leftJoin(c).on(c.CANCELLATION_ID.eq(d.CANCELLATION_ID)).where(d.APPROVAL_ID.eq(approvalId.value)).fetchOne { row(it, it.get(c.STATUS)) }
    }

    @Transactional(readOnly = true)
    override fun findLegacy(approvalId: OperatorApprovalId): LegacyOperatorCancellationRow? = AdminReadLimits.guard {
        val c = PAYMENT_CANCELLATION
        val d = OPERATOR_APPROVAL_DECISION
        val openedAt = UtcWallClock.iso(c.OPENED_AT)
        dsl.select(c.CANCELLATION_ID, c.PAYMENT_ID, c.AMOUNT_MINOR, c.CURRENCY, c.REQUESTED_BY, c.APPROVED_BY, c.APPROVED_TARGET_FINGERPRINT, c.STATUS, openedAt)
            .hint(AdminReadLimits.HINT)
            .from(c)
            .where(c.APPROVAL_ID.eq(approvalId.value))
            .andNotExists(DSL.selectOne().from(d).where(d.APPROVAL_ID.eq(approvalId.value)))
            .fetchOne { r ->
                LegacyOperatorCancellationRow(
                    approvalId, CancellationId(r.get(c.CANCELLATION_ID)), r.get(c.PAYMENT_ID), r.get(c.AMOUNT_MINOR), r.get(c.CURRENCY), r.get(c.REQUESTED_BY).orEmpty(),
                    r.get(c.APPROVED_BY).orEmpty(), r.get(c.APPROVED_TARGET_FINGERPRINT).orEmpty(), CancellationStatus.valueOf(r.get(c.STATUS)), Instant.parse(r.get(openedAt)),
                )
            }
    }

    @Transactional(readOnly = true)
    override fun after(watermark: Long, limit: Int): OperatorDecisionPage = AdminReadLimits.guard {
        val d = OPERATOR_APPROVAL_DECISION
        val c = PAYMENT_CANCELLATION
        val serverTime = dsl.select(UtcWallClock.iso(DSL.field("NOW(3)", java.time.LocalDateTime::class.java))).hint(AdminReadLimits.HINT).fetchOne(0, String::class.java)
        val rows = select(c.STATUS).from(d).leftJoin(c).on(c.CANCELLATION_ID.eq(d.CANCELLATION_ID)).where(d.SEQ.gt(watermark)).orderBy(d.SEQ).limit(limit)
            .fetch { row(it, it.get(c.STATUS)) }
        OperatorDecisionPage(rows, Instant.parse(checkNotNull(serverTime)))
    }

    private val decided = UtcWallClock.iso(OPERATOR_APPROVAL_DECISION.DECIDED_AT).`as`("decided_at_utc")
    private val approved = UtcWallClock.iso(OPERATOR_APPROVAL_DECISION.APPROVED_AT).`as`("approved_at_utc")
    private val before = UtcWallClock.iso(OPERATOR_APPROVAL_DECISION.EXECUTE_BEFORE).`as`("execute_before_utc")
    private val recorded = UtcWallClock.iso(OPERATOR_APPROVAL_DECISION.RECORDED_AT).`as`("recorded_at_utc")

    private fun select(vararg extra: org.jooq.SelectField<*>) = OPERATOR_APPROVAL_DECISION.let { d ->
        dsl.select(
            listOf(
                d.SEQ, d.APPROVAL_ID, d.PAYMENT_ID, d.DECISION, d.REJECTION_CODE, d.CANCELLATION_ID, d.CONTENT_HASH, d.AMOUNT_MINOR, d.CURRENCY, d.REQUESTED_BY, d.APPROVED_BY,
                d.APPROVED_TARGET_FINGERPRINT, d.ORIGIN, decided, approved, before, recorded,
            ) + extra,
        ).hint(AdminReadLimits.HINT)
    }

    private fun row(r: Record, cancellationStatus: String?): OperatorDecisionRow {
        val d = OPERATOR_APPROVAL_DECISION
        return OperatorDecisionRow(
            seq = r.get(d.SEQ),
            decision = OperatorApprovalDecision(
                OperatorApprovalId(r.get(d.APPROVAL_ID)), PaymentId(r.get(d.PAYMENT_ID)), OperatorDecisionKind.valueOf(r.get(d.DECISION)), r.get(d.REJECTION_CODE),
                r.get(d.CANCELLATION_ID)?.let(::CancellationId), r.get(d.CONTENT_HASH), r.get(d.AMOUNT_MINOR), CurrencyCode(r.get(d.CURRENCY)), OperatorId(r.get(d.REQUESTED_BY)),
                OperatorId(r.get(d.APPROVED_BY)), r.get(d.APPROVED_TARGET_FINGERPRINT), Instant.parse(r.get(approved)), Instant.parse(r.get(before)), Instant.parse(r.get(decided)),
                OperatorDecisionOrigin.valueOf(r.get(d.ORIGIN)),
            ),
            cancellationStatus = cancellationStatus?.let(CancellationStatus::valueOf),
            recordedAt = Instant.parse(r.get(recorded)),
        )
    }
}

/**
 * 관리 조회 질의 시간 상한(ADMIN-PAYMENT-OPS-001 §4.7, I-09): MySQL 옵티마이저 hint `MAX_EXECUTION_TIME(2000)`을 SELECT에 붙이고(서버 강제), 초과 오류 3024는 [QueryTimedOut], 그 밖
 * 접근 실패는 [PersistenceFailure.Unavailable]로 옮긴다.
 */
internal object AdminReadLimits {
    const val MAX_EXECUTION_MILLIS = 2000
    const val HINT = "/*+ MAX_EXECUTION_TIME($MAX_EXECUTION_MILLIS) */"
    /** MySQL ER_QUERY_TIMEOUT(3024) 문구(query adapter는 JDBC 타입에 의존하지 않는다 — Spring `QueryTimeoutException` 번역이 없을 때의 분류). */
    private const val ER_QUERY_TIMEOUT_TEXT = "maximum statement execution time exceeded"

    fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: DataAccessException) {
        if (timedOut(e)) throw QueryTimedOut(null) else throw PersistenceFailure.Unavailable(e)
    } catch (e: org.jooq.exception.DataAccessException) {
        if (timedOut(e)) throw QueryTimedOut(null) else throw PersistenceFailure.Unavailable(e)
    }

    private fun timedOut(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(MAX_DEPTH).any {
        it is org.springframework.dao.QueryTimeoutException || it.message?.contains(ER_QUERY_TIMEOUT_TEXT, ignoreCase = true) == true
    }

    private const val MAX_DEPTH = 10
}
