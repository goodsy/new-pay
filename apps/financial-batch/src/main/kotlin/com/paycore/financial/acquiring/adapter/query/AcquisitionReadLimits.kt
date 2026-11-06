package com.paycore.financial.acquiring.adapter.query

import com.paycore.financial.acquiring.application.provided.AcquisitionViewFailure
import org.springframework.dao.DataAccessException
import org.springframework.dao.QueryTimeoutException

/**
 * 관리 조회 질의 시간 상한(ACQ-VIEW-001 §3.2 — payment-api `AdminReadLimits`와 같은 값·방식): MySQL 옵티마이저 hint `MAX_EXECUTION_TIME(2000)`을 모든 SELECT에 붙이고(서버 강제),
 * 초과 오류 3024는 [AcquisitionViewFailure.TimedOut], 그 밖 접근 실패는 [AcquisitionViewFailure.Unavailable]로 옮긴다(원인 문구는 담지 않는다 — 드라이버 원문에 값이 섞일 수 있다).
 * 2초는 Demo 값(운영값 LIVE Gate — 명세 L-5).
 */
internal object AcquisitionReadLimits {
    const val MAX_EXECUTION_MILLIS = 2000
    const val HINT = "/*+ MAX_EXECUTION_TIME($MAX_EXECUTION_MILLIS) */"

    /** MySQL ER_QUERY_TIMEOUT(3024) 문구(query adapter는 JDBC 타입에 의존하지 않는다 — Spring `QueryTimeoutException` 번역이 없을 때의 분류). */
    private const val ER_QUERY_TIMEOUT_TEXT = "maximum statement execution time exceeded"
    private const val MAX_DEPTH = 10

    fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: DataAccessException) {
        throw if (timedOut(e)) AcquisitionViewFailure.TimedOut() else AcquisitionViewFailure.Unavailable()
    } catch (e: org.jooq.exception.DataAccessException) {
        throw if (timedOut(e)) AcquisitionViewFailure.TimedOut() else AcquisitionViewFailure.Unavailable()
    }

    private fun timedOut(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(MAX_DEPTH).any {
        it is QueryTimeoutException || it.message?.contains(ER_QUERY_TIMEOUT_TEXT, ignoreCase = true) == true
    }
}
