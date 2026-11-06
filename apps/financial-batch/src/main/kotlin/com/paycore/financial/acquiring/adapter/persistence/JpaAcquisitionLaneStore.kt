package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * [AcquisitionLaneStore] 구현(JPA, MANDATORY TX).
 *
 * JPA 선택(이번 코드에 연결): 자리 확보는 native IODKU(`ON DUPLICATE KEY UPDATE version = version` — 첫 경쟁을 PK로 직렬화, paymentfact 직렬화 행과 같은 관례),
 * 잠금은 `find` + `refresh(PESSIMISTIC_WRITE)`(= `SELECT … FOR UPDATE` — IODKU는 영속성 컨텍스트를 거치지 않으므로 refresh로 최신 커밋 값을 다시 읽는다).
 * 갱신은 잠가 읽은 Entity의 변경 감지(마지막 봉인 업무일·갱신 시각) + `@Version`이고, flush로 이 호출 안에서 실패를 분류한다(커밋은 Use Case TX 끝).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionLaneStore(private val entityManager: EntityManager) : AcquisitionLaneStore {

    override fun lock(acquirerCode: String, environment: AcquisitionEnvironment, at: Instant): LocalDate? = translate(LANE) {
        entityManager.createNativeQuery(
            "INSERT INTO acquisition_lane (acquirer_code, environment, last_sealed_business_date, created_at, updated_at, version) " +
                "VALUES (:acquirer, :environment, NULL, :at, :at, 0) ON DUPLICATE KEY UPDATE version = version",
        ).setParameter("acquirer", acquirerCode).setParameter("environment", environment.name).setParameter("at", at).executeUpdate()
        val row = checkNotNull(entityManager.find(JpaAcquisitionLaneEntity::class.java, AcquisitionLaneKey(acquirerCode, environment.name))) {
            "lane row must exist after insert-if-absent"
        }
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE)
        row.lastSealedBusinessDate
    }

    override fun markSealed(acquirerCode: String, environment: AcquisitionEnvironment, businessDate: LocalDate, at: Instant): Unit = translate(LANE) {
        val row = checkNotNull(entityManager.find(JpaAcquisitionLaneEntity::class.java, AcquisitionLaneKey(acquirerCode, environment.name))) {
            "lane must be locked first"
        }
        row.lastSealedBusinessDate = businessDate
        row.updatedAt = at
        entityManager.flush()
    }

    override fun lastSealed(acquirerCode: String, environment: AcquisitionEnvironment): LocalDate? = translate(LANE) {
        // native 스칼라(잠금 없음): 영속성 컨텍스트에 lane Entity를 올리지 않는다 — 같은 TX에서 뒤에 잠그는 경로가 stale 1차 캐시 값을 보지 않게.
        entityManager.createNativeQuery(
            "SELECT last_sealed_business_date FROM acquisition_lane WHERE acquirer_code = :acquirer AND environment = :environment",
            LocalDate::class.java,
        ).setParameter("acquirer", acquirerCode).setParameter("environment", environment.name).resultList.firstOrNull() as LocalDate?
    }

    private companion object {
        const val LANE = "acquisition_lane"
    }
}
