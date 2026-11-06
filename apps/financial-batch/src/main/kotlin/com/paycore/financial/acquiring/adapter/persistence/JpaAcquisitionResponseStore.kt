package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquisitionResponseStore
import com.paycore.financial.acquiring.application.required.KnownResponse
import com.paycore.financial.acquiring.application.required.ResponseRecord
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [AcquisitionResponseStore] 구현(JPA, MANDATORY TX). 처리 여부 조회는 native 스칼라(이름·hash·크기 — 응답 BLOB를 읽지 않는다), 기록은 persist + 즉시 flush
 * (IDENTITY id 확보와 UNIQUE(매입사, 이름, hash) 위반 분류를 이 호출 안에서).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionResponseStore(private val entityManager: EntityManager) : AcquisitionResponseStore {

    override fun known(acquirerCode: String, names: Collection<String>): Map<String, List<KnownResponse>> = translate(RESPONSE) {
        names.distinct().chunked(CHUNK).flatMap { chunk ->
            entityManager.createNativeQuery(
                "SELECT file_name, content_sha256, content_bytes FROM acquisition_response WHERE acquirer_code = :acquirer AND file_name IN (:names)",
            ).setParameter("acquirer", acquirerCode).setParameter("names", chunk).resultList.map { row ->
                val r = row as Array<*>
                (r[0] as String) to KnownResponse(r[1] as String, (r[2] as Number).toLong())
            }
        }.groupBy({ it.first }, { it.second })
    }

    override fun append(response: ResponseRecord): Long = translate(RESPONSE) {
        val row = JpaAcquisitionResponseEntity.newRow(
            acquirerCode = response.acquirerCode, environment = response.environment.name, fileName = response.fileName, contentSha256 = response.contentSha256,
            content = response.content, kind = response.kind.name, fileId = response.fileId?.value, sequenceNo = response.seq, disposition = response.disposition.name,
            quarantineReason = response.quarantineReason?.name, ackOutcome = response.ackOutcome, ackReasonCode = response.ackReasonCode,
            institutionAt = response.institutionAt, evidence = response.evidence?.name, observedAt = response.observedAt,
        )
        entityManager.persist(row)
        entityManager.flush()
        checkNotNull(row.id) { "an identity id is assigned on insert" }
    }

    private companion object {
        const val CHUNK = 500
        const val RESPONSE = "acquisition_response"
    }
}
