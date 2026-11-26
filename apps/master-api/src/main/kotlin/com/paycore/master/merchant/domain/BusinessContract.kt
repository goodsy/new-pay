package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 사업자 계약 Aggregate(MERCHANT-FLOW-001 §4B — MASTER 소유, 사업자 1:N, 접수 건과 분리 — BR-BIZ-313). 순수 Domain: Spring·JPA·jOOQ에 의존하지 않는다
 * (DB 행 모양은 `adapter.persistence`의 `JpaBusinessContract*Entity`, 저장 Port `BusinessContractRepository`).
 *
 * - 한 계약 = 한 일관성 경계: 계약 행 + 버전([versions], 범위 포함)·닫기([closes])·정지/해지 사건([events])을 함께 load한다. 상태는 저장하지 않고 [statusAt]이 계산한다(§4B.4).
 * - 결정은 새 행만 만든다([BusinessContractChange]) — 이 객체는 불변이고 명령은 새 상태를 돌려준다. 금융 효과 없음(BR-BIZ-313·321): 결제 허용은 가맹점 계약 Revision 승인(2인)뿐.
 * - 판정 시각 `t`는 Application이 사업자 행 잠금 뒤 얻은 `lockedNow`다(BR-BIZ-331). KST 경계는 고정 `Asia/Seoul`([SeoulMidnight]).
 * - 명령 판정 순서(§4B.7): 사업자 생명주기(`BUSINESS_NOT_ACTIVE`) → 기대값(사건 수 — `BUSINESS_CONTRACT_STATE_CONFLICT`) → 상태 전이표 → 기간·범위 규칙.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class BusinessContract private constructor(
    val id: BusinessContractId,
    val originIntakeSeq: Int,
    val migrated: Boolean,
    val createdAt: Instant,
    val createdBy: ActorRef,
    val versions: List<BusinessContractVersion>,
    val closes: List<BusinessContractVersionClose>,
    val events: List<BusinessContractEvent>,
) {
    init {
        require(originIntakeSeq >= 1) { "origin intake sequence starts at 1" }
        require(versions.isNotEmpty()) { "a contract has at least version 1" }
        require(versions.map { it.versionNo } == (1..versions.size).toList()) { "versions are numbered 1, 2, … without gaps" }
        require(versions.first().originIntakeSeq == originIntakeSeq) { "version 1 comes from the intake that created the contract" }
        require(versions.map { it.originIntakeSeq }.toSet().size == versions.size) { "one intake creates at most one version (I-2)" }
        require(versions.drop(1).none { it.migrated }) { "only version 1 can be a migrated version" }
        require(versions.first().migrated == migrated) { "a migrated contract has a migrated version 1 and only it" }
        require(!migrated || (id.contractSeq == 1 && originIntakeSeq == 1)) { "a migrated contract is contract 1 of migrated intake 1" }
        versions.zipWithNext().forEach { (previous, next) ->
            require(next.startsAt.isAfter(previous.startsAt)) { "a new version starts after the previous one (no overlap — BR-BIZ-317)" }
            val previousEnd = previous.endDate
            val nextStart = next.startDate
            require(previousEnd == null || nextStart == null || !nextStart.isAfter(previousEnd)) { "a new version starts no later than the previous end date (BR-BIZ-346)" }
        }
        require(closes.map { it.versionNo } == (1 until versions.size).toList()) { "every earlier version has exactly one close row" }
        require(closes.all { it.closedAt == versions[it.versionNo].startsAt }) { "a version is closed at the next version's start" }
        require(events.map { it.eventSeq } == (1..events.size).toList()) { "events are numbered 1, 2, … without gaps" }
        require(events.zipWithNext().all { (a, b) -> !b.occurredAt.isBefore(a.occurredAt) }) { "event times never go backwards" }
        val terminateAt = events.indexOfFirst { it.kind == BusinessContractEventKind.TERMINATE }
        require(terminateAt == -1 || terminateAt == events.lastIndex) { "TERMINATE is the last event (I-6)" }
        events.filter { it.kind != BusinessContractEventKind.TERMINATE }.forEachIndexed { i, e ->
            val expected = if (i % 2 == 0) BusinessContractEventKind.SUSPEND else BusinessContractEventKind.RESUME
            require(e.kind == expected) { "suspend / resume alternate, starting with SUSPEND" }
        }
    }

    // ------------------------------------------------------------------ 계산(§4B.4 — 앞이 이긴다)

    /**
     * 판정 시각 [t]의 상태: ① 해지 사건 → 해지 ② 마지막 버전 만료 시각 ≤ t → 만료 ③ 마지막 정지 계열 사건이 정지 → 정지 ④ t < 버전 1 시작 → 효력 전 ⑤ 그 밖 유효.
     * **판정용(fail-closed — 재리뷰 MAJOR-1)**: 저장된 사건은 발생 시각과 관계없이 **모두** 센다. 판정 시각 [t]가 마지막 사건보다 앞서면(서버 시계 역행) 시각 기준도
     * [judgedAt] = max(t, 마지막 사건 시각)으로 올린다 — 해지·정지가 기록된 계약은 어떤 t로 물어도 해지·정지로 판정된다(관문 `statusAt(lockedNow) = ACTIVE`가 해지 계약을 통과시키지 않음).
     * 과거 시점 표시용 계산은 이 함수가 아니다(필요해지면 조회 전용 이름으로 따로 둔다).
     * (중간 버전은 다음 버전 시작으로 닫히므로 공백이 없다 — §4B.3.)
     */
    fun statusAt(t: Instant): BusinessContractStatus {
        val time = judgedAt(t)
        if (events.any { it.kind == BusinessContractEventKind.TERMINATE }) return BusinessContractStatus.TERMINATED
        val expiresAt = versions.last().expiresAt
        if (expiresAt != null && !time.isBefore(expiresAt)) return BusinessContractStatus.EXPIRED
        if (events.lastOrNull { it.kind != BusinessContractEventKind.TERMINATE }?.kind == BusinessContractEventKind.SUSPEND) return BusinessContractStatus.SUSPENDED
        if (time.isBefore(versions.first().startsAt)) return BusinessContractStatus.NOT_YET_EFFECTIVE
        return BusinessContractStatus.ACTIVE
    }

    /** '계약 있음'(BR-BIZ-325 — 효력 전·유효·정지). [statusAt]과 같은 판정 기준. */
    fun hasContractAt(t: Instant): Boolean = statusAt(t).hasContract

    /**
     * 명령 판정 시각 = max([at], 마지막 사건 시각). 잠금 뒤 시각([at])이 저장된 사건보다 앞서도(서버 시계 차) 이미 기록된 정지·해지를 놓치지 않고, 새 사건 시각도 뒤로 가지 않는다.
     */
    fun judgedAt(at: Instant): Instant = events.lastOrNull()?.occurredAt?.takeIf { it.isAfter(at) } ?: at

    /** 버전 [versionNo]의 실제 끝 = min(닫는 시각, 만료 시각), 둘 다 없으면 null(열린 끝). */
    fun actualEndOf(versionNo: Int): Instant? {
        val version = versions[versionNo - 1]
        val closedAt = closes.firstOrNull { it.versionNo == versionNo }?.closedAt
        val expiresAt = version.expiresAt
        return listOfNotNull(closedAt, expiresAt).minOrNull()
    }

    /** 버전 [versionNo]의 [t] 기준 단계(GOV-MER-ELIG-001 §2.3). */
    fun phaseOf(versionNo: Int, t: Instant): BusinessContractVersionPhase {
        val version = versions[versionNo - 1]
        val end = actualEndOf(versionNo)
        return when {
            t.isBefore(version.startsAt) -> BusinessContractVersionPhase.SCHEDULED
            end != null && !t.isBefore(end) -> BusinessContractVersionPhase.ENDED
            else -> BusinessContractVersionPhase.APPLYING
        }
    }

    /** 적용 중 버전(시작 ≤ t < 실제 끝 — 최대 1개, I-4). 기간만 본다 — 관문은 [statusAt] = 유효인지 따로 본다(FLOW-3). */
    fun applyingVersionAt(t: Instant): BusinessContractVersion? = versions.firstOrNull { phaseOf(it.versionNo, t) == BusinessContractVersionPhase.APPLYING }

    /** 예정 버전(시작 > t — 계약당 최대 1개, BR-BIZ-341). 첫 버전이 시작 전인 효력 전 계약도 포함. */
    fun scheduledVersionAt(t: Instant): BusinessContractVersion? = versions.lastOrNull { t.isBefore(it.startsAt) }

    // ------------------------------------------------------------------ 명령(새 행만 돌려준다)

    /**
     * 변경 계약 완료(BR-BIZ-324·333·341·345·346): 이 계약에 새 버전 + 직전 버전 닫기. 판정 순서: 생명주기 → 해지·만료(`BUSINESS_CONTRACT_NOT_CHANGEABLE`) →
     * 예정 버전 있음(`…_SCHEDULED_VERSION_EXISTS`) → 범위·시작일 소급·기간(§4B.3). 정지 중이면 정지는 유지된다(BR-BIZ-326 — 사건을 만들지 않으므로).
     */
    fun addVersion(
        business: Business,
        originIntakeSeq: Int,
        terms: BusinessContractTerms,
        document: ContractDocumentRef,
        reason: BusinessChangeReason?,
        at: Instant,
        by: ActorRef,
    ): BusinessContractChange {
        requireBusiness(business)
        require(versions.none { it.originIntakeSeq == originIntakeSeq }) { "one intake creates at most one version (I-2)" }
        if (statusAt(at).isClosed) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE)
        if (scheduledVersionAt(at) != null) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS)
        val previous = versions.last()
        val version = newVersion(previous.versionNo + 1, originIntakeSeq, terms, document, reason, at, by)
        val previousEnd = previous.endDate
        if (!version.startsAt.isAfter(previous.startsAt)) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID)
        if (previousEnd != null && terms.startDate.isAfter(previousEnd)) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID)
        val close = BusinessContractVersionClose(previous.versionNo, version.startsAt, version.versionNo, at, by)
        val next = BusinessContract(id, this.originIntakeSeq, migrated, createdAt, createdBy, versions + version, closes + close, events)
        return BusinessContractChange(next, false, version, close, null)
    }

    /** 정지(BR-BIZ-336·344): 효력 전·유효 → 정지. 정지·해지·만료는 `BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED`. 즉시 효력(예약 없음). */
    fun suspend(business: Business, expectedEventCount: Int, reason: BusinessChangeReason, at: Instant, by: ActorRef): BusinessContractChange {
        requireCommand(business, expectedEventCount)
        val status = statusAt(at)
        if (status.isClosed || status == BusinessContractStatus.SUSPENDED) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED)
        return withEvent(BusinessContractEventKind.SUSPEND, reason, null, at, by)
    }

    /** 정지 해제: 정지 → (다시 계산 — 유효 또는 효력 전). 그 밖 `BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED`. 정지 이전 상태를 되살리는 기록이 아니다(§4B.4). */
    fun resume(business: Business, expectedEventCount: Int, reason: BusinessChangeReason, at: Instant, by: ActorRef): BusinessContractChange {
        requireCommand(business, expectedEventCount)
        if (statusAt(at) != BusinessContractStatus.SUSPENDED) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED)
        return withEvent(BusinessContractEventKind.RESUME, reason, null, at, by)
    }

    /** 해지(BR-BIZ-336·339·342·344): 효력 전·유효·정지 → 해지(종결, 사유 필수·근거 선택). 해지·만료는 `BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED`. */
    fun terminate(
        business: Business,
        expectedEventCount: Int,
        reason: BusinessChangeReason,
        evidence: ContractDocumentRef?,
        at: Instant,
        by: ActorRef,
    ): BusinessContractChange {
        requireCommand(business, expectedEventCount)
        if (statusAt(at).isClosed) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED)
        return withEvent(BusinessContractEventKind.TERMINATE, reason, evidence, at, by)
    }

    private fun withEvent(kind: BusinessContractEventKind, reason: BusinessChangeReason, evidence: ContractDocumentRef?, at: Instant, by: ActorRef): BusinessContractChange {
        // 기록 시각은 뒤로 가지 않는다(= [judgedAt] — ContractIntake.transitionTo의 max(판정 시각, 저장 시각)과 같은 관례).
        val event = BusinessContractEvent(events.size + 1, kind, reason, evidence, by, judgedAt(at))
        val next = BusinessContract(id, originIntakeSeq, migrated, createdAt, createdBy, versions, closes, events + event)
        return BusinessContractChange(next, false, null, null, event)
    }

    private fun requireBusiness(business: Business) {
        require(business.id == id.businessId) { "contract belongs to another business" }
        business.requireActive()
    }

    private fun requireCommand(business: Business, expectedEventCount: Int) {
        requireBusiness(business)
        if (expectedEventCount != events.size) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_STATE_CONFLICT)
    }

    override fun toString(): String =
        "BusinessContract(businessId=${id.businessId.value}, contractSeq=${id.contractSeq}, migrated=$migrated, versions=${versions.size}, events=${events.size})"

    companion object {
        /**
         * 신규 계약 완료(BR-BIZ-314·315·324): [latest] = 그 사업자의 최신 사업자 계약(잠금 뒤 같은 TX에서 읽은 값, 없으면 null). 판정 순서: 생명주기 → 최신 계약이
         * '계약 있음'이면 `BUSINESS_CONTRACT_EXISTS`(I-1 — 정상 흐름에서는 접수 종류 판정이 먼저 막는 방어) → 범위·시작일 소급·기간. 순번 = 최신 + 1.
         * 사업자 DRAFT도 허용(BR-BIZ-332).
         */
        fun create(
            business: Business,
            latest: BusinessContract?,
            originIntakeSeq: Int,
            terms: BusinessContractTerms,
            document: ContractDocumentRef,
            reason: BusinessChangeReason?,
            at: Instant,
            by: ActorRef,
        ): BusinessContractChange {
            business.requireActive()
            if (latest != null) {
                require(latest.id.businessId == business.id) { "latest contract belongs to another business" }
                if (latest.hasContractAt(at)) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_EXISTS)
            }
            val version = newVersion(1, originIntakeSeq, terms, document, reason, at, by)
            val contract = BusinessContract(
                BusinessContractId(business.id, (latest?.id?.contractSeq ?: 0) + 1), originIntakeSeq, false, at, by, listOf(version), emptyList(), emptyList(),
            )
            return BusinessContractChange(contract, true, version, null, null)
        }

        /** 버전 조건 검사(§4B.3): 범위 1개 이상 → 시작일 ≥ KST 오늘(소급 금지 — 오늘 허용) → 종료일 ≥ 시작일. */
        private fun newVersion(
            versionNo: Int,
            originIntakeSeq: Int,
            terms: BusinessContractTerms,
            document: ContractDocumentRef,
            reason: BusinessChangeReason?,
            at: Instant,
            by: ActorRef,
        ): BusinessContractVersion {
            if (terms.services.isEmpty()) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_SCOPE_INVALID)
            if (terms.startDate.isBefore(at.atZone(SeoulMidnight.ZONE).toLocalDate())) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_START_RETROACTIVE)
            val end = terms.endDate
            if (end != null && end.isBefore(terms.startDate)) throw MasterRuleViolation(MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID)
            return BusinessContractVersion(
                versionNo, originIntakeSeq, false, BusinessContractVersion.kstMidnight(terms.startDate), terms.startDate, end, terms.services, document, reason, at, by,
            )
        }

        /** persistence Mapper 전용 복원(이관 계약 포함 — 이관 행은 V21 migration SQL만 만든다, §4B.10 5). 불변식을 어긴 저장 행은 load하지 않는다. */
        fun reconstitute(
            id: BusinessContractId,
            originIntakeSeq: Int,
            migrated: Boolean,
            createdAt: Instant,
            createdBy: ActorRef,
            versions: List<BusinessContractVersion>,
            closes: List<BusinessContractVersionClose>,
            events: List<BusinessContractEvent>,
        ) = BusinessContract(id, originIntakeSeq, migrated, createdAt, createdBy, versions, closes, events)
    }
}
