package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.BusinessWithProfile
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessAddress
import com.paycore.master.merchant.domain.BusinessCategory
import com.paycore.master.merchant.domain.BusinessContact
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.BusinessRepresentative
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 사업자 Aggregate 저장(BUSINESS-PROFILE-001 §5.5 — JPA 선택의 역할·예·이유·주의는 명세 §5.5에 있다).
 *
 * - **Root 전용**([lock]·[find]·[findIdByRegistrationNumber])은 하위 표를 읽지 않는다(m-3 — 가맹점·계약 등록·권리 원천 경로 불변, BIZ-1-3이 SQL로 확인).
 * - **연관 매핑 없음**: 하위 행은 자식 Spring Data repository로 따로 읽고 쓴다. 대표자 교체 = 기존 행 `effective_to` 기록 + 새 행 `persist`
 *   (cascade·orphanRemoval이 종료 행을 지우는 경로가 구조적으로 없다 — 대신 자식 저장 누락을 JPA가 알려 주지 않으므로 저장 후 재조회 시험이 고정한다).
 * - **flush 순서**: Hibernate는 flush 때 INSERT를 UPDATE보다 먼저 실행하고, IDENTITY 행 ID는 `persist` 즉시 INSERT한다. 그대로 두면 새 기본 담당자·같은 순서 새 행의 INSERT가
 *   아직 닫히지 않은 옛 행과 현재 행 UNIQUE(생성 열)에서 1062가 난다 → **종료 UPDATE 뒤 명시 flush, 그다음 새 행 persist**(고정 순서 — BIZ-1-5 변이 (b)).
 * - **version 정확히 +1**: Root 열이 바뀌면 변경 감지 UPDATE가 @Version을 +1 한다. 하위 행만 바뀌어 Root 열이 그대로면(같은 ms 시계·같은 행위자)
 *   `PESSIMISTIC_FORCE_INCREMENT`로 +1 한다(둘 다 하면 +2 — MERCHANT-CODE-001 MC-4 교훈, JpaMerchantRepository와 같은 방식). 저장 뒤 version을 확인한다.
 * - **stale 덮어쓰기 방지**: 요청 version은 Domain 전이가 잠근 행 version과 명시 비교했다(`BUSINESS_VERSION_CONFLICT`). 여기서는 잠근 행 version = 전이 전 version,
 *   현재 하위 행 = 전이 전 profile인지 다시 확인한다(결함 방어 — 잠금 우회 시 덮어쓰지 않는다).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
@Suppress("LongParameterList")
class JpaBusinessRepository(
    private val businesses: BusinessJpaRepository,
    private val registrations: BusinessRegistrationJpaRepository,
    private val representatives: BusinessRepresentativeJpaRepository,
    private val categories: BusinessCategoryJpaRepository,
    private val addresses: BusinessAddressJpaRepository,
    private val contacts: BusinessContactJpaRepository,
    private val entityManager: EntityManager,
) : BusinessRepository {

    override fun insert(business: Business): Unit = insert(business, BusinessProfile.EMPTY)

    override fun insert(business: Business, profile: BusinessProfile): Unit = translate(ENTITY) {
        val row = MasterPersistenceMapper.businessRow(business)
        entityManager.persist(row)
        // 번호 중복(1062 uk_business_registration_number — 업무 유일키)은 여기서 드러난다(commit까지 미루지 않는다).
        entityManager.flush()
        val registration = BusinessPersistenceMapper.registrationRow(business.id.value, profile.registration)
        entityManager.persist(registration)
        val registrationId = checkNotNull(registration.registrationId) { "registration id is assigned on persist (IDENTITY)" }
        val canonical = profile.canonical()
        val at = business.updatedAt
        val by = business.updatedBy.value
        canonical.representatives.forEachIndexed { i, r -> persistRepresentative(business.id.value, r to i + 1, at, by) }
        canonical.orderedCategories().forEach { persistCategory(registrationId, it, at, by) }
        canonical.addresses.forEach { persistAddress(registrationId, it, at, by) }
        canonical.contacts.forEachIndexed { i, c -> persistContact(business.id.value, c to i + 1, at, by) }
        entityManager.flush()
        check(row.version == business.version) { "a new business starts at its domain version" }
    }

    override fun lock(id: BusinessId): Business? = translate(ENTITY) {
        entityManager.lockRow(JpaBusinessEntity::class.java, id.value)?.let(MasterPersistenceMapper::toBusiness)
    }

    override fun lockWithProfile(id: BusinessId): BusinessWithProfile? = translate(ENTITY) {
        val row = entityManager.lockRow(JpaBusinessEntity::class.java, id.value) ?: return@translate null
        BusinessWithProfile(MasterPersistenceMapper.toBusiness(row), currentRows(id.value).profile())
    }

    override fun update(business: Business, before: BusinessProfile, after: BusinessProfile): Unit = translate(ENTITY) {
        val id = business.id.value
        val row = entityManager.find(JpaBusinessEntity::class.java, id) ?: error("business is not stored")
        if (row.version != business.version - 1) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.registrationNumber == business.registrationNumber.value) { "a business registration number never changes" }
        val rows = currentRows(id)
        check(rows.profile() == before.canonical()) { "update must start from the profile read under the business lock" }
        val target = after.canonical()
        val at = business.updatedAt
        val by = business.updatedBy.value
        val registrationId = checkNotNull(rows.registration.registrationId)

        // ① 사라진 현재 행 종료(effective_to·ended_by — 유일한 하위 행 UPDATE). 짝짓기 키 = 내용 + 순서(§4.1·M-4 — 순서만 바뀌어도 종료 + 추가).
        val newRepresentatives = endMissing(rows.representatives, { BusinessPersistenceMapper.toRepresentative(it) to it.displayOrder },
            target.representatives.mapIndexed { i, r -> r to i + 1 }, at, by) { r, t, b -> r.end(t, b) }
        val newCategories = endMissing(rows.categories, { BusinessPersistenceMapper.toCategory(it) to it.displayOrder }, target.orderedCategories(), at, by) { r, t, b ->
            r.end(t, b)
        }
        val newAddresses = endMissing(rows.addresses, BusinessPersistenceMapper::toAddress, target.addresses, at, by) { r, t, b -> r.end(t, b) }
        val newContacts = endMissing(rows.contacts, { BusinessPersistenceMapper.toContact(it) to it.displayOrder },
            target.contacts.mapIndexed { i, c -> c to i + 1 }, at, by) { r, t, b -> r.end(t, b) }

        // ② 등록정보 제자리·Root 열(변경 감지).
        val registration = target.registration
        rows.registration.apply(
            registration.corporateRegistrationNumber?.value, registration.openingDate, registration.englishName?.value, registration.phoneNumber?.value,
            registration.email?.value,
        )
        check(rows.registration.countryCode == registration.countryCode) { "country code is fixed (KR)" }
        val rootChanged = row.applyProfile(business.displayName.value, business.businessType?.name, business.registrationStatus.name, at, by)

        // ③ 종료 UPDATE를 먼저 DB에 반영한다(현재 행 UNIQUE 생성 열 — 새 행 INSERT 전에 옛 행이 닫혀 있어야 한다).
        entityManager.flush()

        // ④ 새 현재 행.
        newRepresentatives.forEach { persistRepresentative(id, it, at, by) }
        newCategories.forEach { persistCategory(registrationId, it, at, by) }
        newAddresses.forEach { persistAddress(registrationId, it, at, by) }
        newContacts.forEach { persistContact(id, it, at, by) }

        // ⑤ version 정확히 +1: Root 열 변경이 없으면(하위 행·등록정보만 바뀜) 강제 증가 한 번.
        if (!rootChanged) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        entityManager.flush()
        check(row.version == business.version) { "business version must advance exactly once per change" }
    }

    override fun findIdByRegistrationNumber(number: BusinessRegistrationNumber): BusinessId? = translate(ENTITY) {
        businesses.findIdByRegistrationNumber(number.value)?.let(::BusinessId)
    }

    override fun find(id: BusinessId): Business? = translate(ENTITY) {
        entityManager.find(JpaBusinessEntity::class.java, id.value)?.let(MasterPersistenceMapper::toBusiness)
    }

    /** 현재 행 묶음(business 잠금 뒤 일반 SELECT — READ COMMITTED 최신 커밋). 등록정보 행이 없으면 1:1 불변식 위반(결함). */
    private fun currentRows(businessId: String): CurrentRows {
        val registration = registrations.findByBusinessId(businessId) ?: error("business registration row is missing (1:1 invariant)")
        val registrationId = checkNotNull(registration.registrationId)
        return CurrentRows(
            registration, representatives.findCurrent(businessId), categories.findCurrent(registrationId), addresses.findCurrent(registrationId),
            contacts.findCurrent(businessId),
        )
    }

    /** [current] 중 [wanted]에 없는 행을 종료하고, 아직 없는 [wanted] 키(추가할 행)를 돌려준다. */
    private fun <E, K> endMissing(current: List<E>, keyOf: (E) -> K, wanted: List<K>, at: Instant, by: String, end: (E, Instant, String) -> Unit): List<K> {
        val currentKeys = current.map(keyOf).toSet()
        val wantedKeys = wanted.toSet()
        current.filter { keyOf(it) !in wantedKeys }.forEach { end(it, at, by) }
        return wanted.filter { it !in currentKeys }
    }

    private fun persistRepresentative(businessId: String, row: Pair<BusinessRepresentative, Int>, at: Instant, by: String) =
        entityManager.persist(JpaBusinessRepresentativeEntity.newRow(businessId, row.first.name.value, row.first.type.name, row.second, at, by))

    private fun persistCategory(registrationId: Long, row: Pair<BusinessCategory, Int>, at: Instant, by: String) =
        entityManager.persist(JpaBusinessCategoryEntity.newRow(registrationId, row.first.type.name, row.first.name.value, row.second, at, by))

    private fun persistAddress(registrationId: Long, a: BusinessAddress, at: Instant, by: String) =
        entityManager.persist(JpaBusinessAddressEntity.newRow(registrationId, a.type.name, a.postalCode.value, a.baseAddress.value, a.detailAddress?.value, at, by))

    private fun persistContact(businessId: String, row: Pair<BusinessContact, Int>, at: Instant, by: String) =
        entityManager.persist(
            JpaBusinessContactEntity.newRow(
                businessId, row.first.name.value, row.first.email.value, row.first.phoneNumber.value, row.first.primary, row.second, at, by,
            ),
        )

    private class CurrentRows(
        val registration: JpaBusinessRegistrationEntity,
        val representatives: List<JpaBusinessRepresentativeEntity>,
        val categories: List<JpaBusinessCategoryEntity>,
        val addresses: List<JpaBusinessAddressEntity>,
        val contacts: List<JpaBusinessContactEntity>,
    ) {
        fun profile(): BusinessProfile = BusinessPersistenceMapper.toProfile(registration, representatives, categories, addresses, contacts)
    }

    private companion object {
        const val ENTITY = "business"
    }
}
