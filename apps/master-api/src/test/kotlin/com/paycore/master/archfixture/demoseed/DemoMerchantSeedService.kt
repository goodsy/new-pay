package com.paycore.master.archfixture.demoseed

import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.AdminReservation
import jakarta.persistence.EntityManager

/**
 * ArchitectureSpec 위반 fixture(시험 전용 — 운영 classpath에 없음): seed 조정자가 진행 판정에 `reserveOrLock`(PENDING 행 INSERT)을 쓰고
 * EntityManager로 직접 접근하는 잘못된 구현. `demoSeedUsesOnlyProvidedUseCases`가 둘 다 잡아야 한다(DEMO-SEED-001 I-4·I-20).
 */
@Suppress("unused")
class DemoMerchantSeedService(private val requests: AdminRequestStore, private val entityManager: EntityManager) {
    fun progress(reservation: AdminReservation) {
        requests.reserveOrLock(reservation)
        entityManager.flush()
    }
}
