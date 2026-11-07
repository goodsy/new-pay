package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquiringPersistenceFailure
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.StoredAcquisitionFile
import com.paycore.financial.acquiring.domain.AcquisitionContractViolation
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFile
import com.paycore.financial.acquiring.domain.AcquisitionFileHeader
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecord
import com.paycore.financial.acquiring.domain.AcquisitionTotals
import com.paycore.financial.acquiring.domain.ReceiptState
import com.paycore.financial.acquiring.domain.RenderedAcquisitionFile
import com.paycore.financial.acquiring.domain.ResultState
import com.paycore.financial.acquiring.domain.TransmissionState
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [AcquisitionFileRepository] 구현(JPA, MANDATORY TX). 봉인 = 파일 행 + 바이트 행 + 레코드 행 persist 후 flush 한 번(UNIQUE·CHECK·FK 위반을 이 호출 안에서
 * 분류 — flush ≠ commit). 조회는 파일 행 → 레코드(seq 순) → 바이트를 각각 file_id로 읽는다(연관 매핑 없음 — N+1·지연 로딩 경로 없음).
 * 행 → domain은 domain 생성자를 다시 거친다(저장된 값이 계약을 어기면 [com.paycore.financial.acquiring.domain.AcquisitionContractViolation]).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionFileRepository(private val entityManager: EntityManager) : AcquisitionFileRepository {

    override fun find(id: AcquisitionFileId): StoredAcquisitionFile? = translate(FILE) {
        val row = entityManager.find(JpaAcquisitionFileEntity::class.java, id.value) ?: return@translate null
        val records = readRecords(id)
        val content = entityManager.find(JpaAcquisitionFileContentEntity::class.java, id.value) ?: throw AcquisitionContractViolation("a sealed file has its content row")
        StoredAcquisitionFile(mapped { toFile(row) }, records, content.content.copyOf(), content.contentSha256)
    }

    override fun findFile(id: AcquisitionFileId): AcquisitionFile? = translate(FILE) {
        entityManager.find(JpaAcquisitionFileEntity::class.java, id.value)?.let { mapped { toFile(it) } }
    }

    override fun lockFile(id: AcquisitionFileId): AcquisitionFile? = translate(FILE) {
        // PESSIMISTIC_WRITE = SELECT … FOR UPDATE(열 단위 UPDATE 권한으로 허용 — FinancialDatabaseAccountSpec "lock read"). 이 TX에서 처음 읽는 행이라 최신 커밋 값이다.
        entityManager.find(JpaAcquisitionFileEntity::class.java, id.value, LockModeType.PESSIMISTIC_WRITE)?.let { mapped { toFile(it) } }
    }

    override fun lockOtherGenerationFiles(id: AcquisitionFileId): List<AcquisitionFileId> = translate(FILE) {
        @Suppress("UNCHECKED_CAST")
        val others = entityManager.createNativeQuery(
            "SELECT DISTINCT o.file_id FROM acquisition_file_record r JOIN acquisition_file_record o " +
                "ON o.transaction_id = r.transaction_id AND o.record_type = r.record_type AND o.file_id <> r.file_id WHERE r.file_id = :fileId ORDER BY o.file_id",
        ).setParameter("fileId", id.value).resultList as List<String>
        // 같은 TX 안에서 파일 ID 오름차순으로 하나씩 잠근다(응답 TX와 같은 행 잠금 — PESSIMISTIC_WRITE).
        others.map { AcquisitionFileId.parse(it) }.onEach { other ->
            entityManager.find(JpaAcquisitionFileEntity::class.java, other.value, LockModeType.PESSIMISTIC_WRITE)
        }
    }

    override fun records(id: AcquisitionFileId): List<AcquisitionRecord> = translate(RECORD) { readRecords(id) }

    override fun latestVersion(acquirerCode: String, environment: AcquisitionEnvironment, businessDate: java.time.LocalDate): Int? = translate(FILE) {
        (
            entityManager.createNativeQuery(
                "SELECT MAX(file_version) FROM acquisition_file WHERE acquirer_code = :acquirer AND environment = :environment AND business_date = :day",
            ).setParameter("acquirer", acquirerCode).setParameter("environment", environment.name).setParameter("day", businessDate).singleResult as Number?
            )?.toInt()
    }

    override fun awaitingResults(acquirerCode: String, environment: AcquisitionEnvironment, transmittedUpTo: Instant, limit: Int): List<AcquisitionFileId> =
        translate(FILE) {
            entityManager.createQuery(
                "select f.fileId from JpaAcquisitionFileEntity f where f.acquirerCode = :acquirer and f.environment = :environment and f.resultState = 'AWAITING' " +
                    "and f.transmissionState = 'TRANSMITTED' and f.transmittedAt <= :upTo order by f.businessDate, f.fileVersion",
                String::class.java,
            ).setParameter("acquirer", acquirerCode).setParameter("environment", environment.name).setParameter("upTo", transmittedUpTo).setMaxResults(limit)
                .resultList.map(AcquisitionFileId::parse)
        }

    private fun readRecords(id: AcquisitionFileId): List<AcquisitionRecord> = entityManager.createQuery(
        "select r from JpaAcquisitionFileRecordEntity r where r.fileId = :fileId order by r.seq", JpaAcquisitionFileRecordEntity::class.java,
    ).setParameter("fileId", id.value).resultList.map { mapped { it.toDomain() } }

    /**
     * 저장 행 → Domain(리뷰 NIT-3): 저장 값이 계약(문법·불변식·열거값)을 어기면 [AcquisitionContractViolation]로 통일한다 — 호출자(봉인·전송)가 INTEGRITY_VIOLATION으로
     * 분류한다(lane을 분류되지 않은 예외로 멈추지 않는다). 메시지에 저장 값을 넣지 않는다.
     */
    private fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: AcquisitionContractViolation) {
        throw e
    } catch (e: IllegalStateException) {
        throw AcquisitionContractViolation("stored acquisition rows do not satisfy the file v1 contract")
    } catch (e: IllegalArgumentException) {
        throw AcquisitionContractViolation("stored acquisition rows do not satisfy the file v1 contract")
    }

    override fun insert(file: AcquisitionFile, rendered: RenderedAcquisitionFile): Unit = translate(FILE) {
        require(file.contentSha256 == rendered.sha256 && file.id == rendered.header.fileId) { "the rendered bytes belong to this file" }
        val h = file.header
        val t = file.totals
        entityManager.persist(
            JpaAcquisitionFileEntity.newRow(
                fileId = h.fileId.value, acquirerCode = h.fileId.acquirerCode, environment = h.fileId.environment.name, businessDate = h.fileId.businessDate,
                fileVersion = h.fileId.version, originalFileId = h.originalFileId?.value, senderId = h.senderId, cutoffPolicyVersion = h.cutoffPolicyVersion,
                cutoffAt = file.cutoffAt, sealedAt = h.sealedAt, recordCount = t.recordCount, saleCount = t.saleCount, saleAmountMinor = t.saleAmountMinor,
                cancelCount = t.cancelCount, cancelAmountMinor = t.cancelAmountMinor, netAmountMinor = t.netAmountMinor, contentSha256 = file.contentSha256,
                contentBytes = file.contentBytes, transmissionState = file.transmissionState.name, receiptState = file.receiptState.name,
                resultState = file.resultState.name, at = h.sealedAt,
            ),
        )
        entityManager.persist(JpaAcquisitionFileContentEntity.newRow(h.fileId.value, rendered.content, rendered.sha256))
        rendered.records.forEach { r ->
            entityManager.persist(
                JpaAcquisitionFileRecordEntity.newRow(
                    fileId = h.fileId.value, seq = r.seq, recordType = r.type.name, transactionId = r.transactionId, originalTransactionId = r.originalTransactionId,
                    merchantRef = r.merchantRef, approvalNumber = r.approvalNumber, providerTransactionId = r.providerTransactionId,
                    originalProviderTransactionId = r.originalProviderTransactionId, institutionTransactionDate = r.institutionTransactionDate, eventAt = r.eventAt,
                    currency = r.currency, approvedAmountMinor = r.approvedAmountMinor, nettedCancelAmountMinor = r.nettedCancelAmountMinor,
                    recordAmountMinor = r.recordAmountMinor, planKind = r.planKind, installmentMonths = r.installmentMonths, cardType = r.cardType,
                    issuerCode = r.issuerCode, generation = r.generation, reacquisitionOf = r.reacquisitionOf,
                ),
            )
        }
        entityManager.flush()
    }

    override fun update(file: AcquisitionFile, at: Instant): Unit = translate(FILE) {
        val row = entityManager.find(JpaAcquisitionFileEntity::class.java, file.id.value) ?: throw IllegalStateException("an updated file exists")
        // stale Domain으로 덮어쓰지 않는다: 이 TX에서 읽은 행 version이 Domain의 기대 version과 다르면 저장하지 않는다(동시 flush 경합은 @Version이 잡는다).
        if (row.version != file.version) throw AcquiringPersistenceFailure.OptimisticConflict(FILE)
        row.applyAxes(file.transmissionState.name, file.transmissionLeaseUntil, file.transmittedAt, file.receiptState.name, file.resultState.name, at)
        entityManager.flush()
    }

    override fun transmittable(acquirerCode: String, environment: AcquisitionEnvironment, now: Instant, limit: Int): List<AcquisitionFileId> = translate(FILE) {
        entityManager.createQuery(
            "select f.fileId from JpaAcquisitionFileEntity f where f.acquirerCode = :acquirer and f.environment = :environment and " +
                "(f.transmissionState in ('SEALED', 'TRANSMISSION_UNKNOWN') or (f.transmissionState = 'TRANSMITTING' and f.transmissionLeaseUntil <= :now)) " +
                "order by f.businessDate, f.fileVersion",
            String::class.java,
        ).setParameter("acquirer", acquirerCode).setParameter("environment", environment.name).setParameter("now", now).setMaxResults(limit)
            .resultList.map(AcquisitionFileId::parse)
    }

    private fun toFile(row: JpaAcquisitionFileEntity): AcquisitionFile {
        val id = AcquisitionFileId(row.acquirerCode, AcquisitionEnvironment.valueOf(row.environment), row.businessDate, row.fileVersion)
        check(id.value == row.fileId) { "stored file id must match its lane, date and version" }
        val totals = AcquisitionTotals(row.recordCount, row.saleCount, row.saleAmountMinor, row.cancelCount, row.cancelAmountMinor)
        check(totals.netAmountMinor == row.netAmountMinor) { "stored net amount must equal sale minus cancel" }
        return AcquisitionFile.restore(
            header = AcquisitionFileHeader(id, row.senderId, row.sealedAt, row.cutoffPolicyVersion, row.originalFileId?.let(AcquisitionFileId::parse)),
            cutoffAt = row.cutoffAt, totals = totals, contentSha256 = row.contentSha256, contentBytes = row.contentBytes,
            transmissionState = TransmissionState.valueOf(row.transmissionState), receiptState = ReceiptState.valueOf(row.receiptState),
            resultState = ResultState.valueOf(row.resultState), transmissionLeaseUntil = row.transmissionLeaseUntil, transmittedAt = row.transmittedAt,
            version = row.version,
        )
    }


    private companion object {
        const val FILE = "acquisition_file"
        const val RECORD = "acquisition_file_record"
    }
}
