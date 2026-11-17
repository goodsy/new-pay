package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.update
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/**
 * 스키마 제약 시험용 원시 SQL 행 작성기(시험 전용, src/test). 애플리케이션 경로를 거치지 않고 DB 제약 자체를 확인하기 위해 쓴다.
 * 연결은 호출자가 고른다(대개 runtime 계정 — 운영과 같은 표 단위 권한). 식별자는 매번 새로 만들어 같은 컨텍스트의 다른 시험과 섞이지 않는다.
 */
object MasterSchemaRows {
    private val random = SecureRandom()
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val ACTOR = "schema-test"

    val t0: Instant = Instant.parse("2026-10-09T00:00:00.000Z")

    fun id(prefix: String): String = prefix + "_" + (1..26).map { CROCKFORD[random.nextInt(CROCKFORD.length)] }.joinToString("")

    /** 10자리 숫자 등록번호(시험 값, 실제 사업자 아님). */
    fun registrationNumber(): String = (1..10).map { random.nextInt(10) }.joinToString("")

    fun channel(): String = "shop-" + id("x").substring(2).lowercase() + ".example.test"

    private fun ts(at: Instant) = Timestamp.from(at)

    /**
     * BUSINESS-PROFILE-001 V15: NOT NULL 새 열(등록 상태·진위확인·갱신 시각/행위자 — 기본값 없음)을 함께 넣는다(migration 행과 같은 DRAFT·구분 NULL 형태).
     * 행 작성 열 목록만 갱신 — 이 작성기를 쓰는 시험의 기대값은 바뀌지 않는다. 등록정보 1행(1:1 불변식)은 [businessRegistration]이 따로 넣는다(제약 시험이 직접 다룬다).
     */
    fun business(c: Connection, regNo: String = registrationNumber()): String = id("biz").also {
        c.update(
            "INSERT INTO business (business_id, registration_number, display_name, lifecycle, created_at, created_by, version, " +
                "registration_status, verification_status, updated_at, updated_by) " +
                "VALUES (?, ?, 'Demo business', 'ACTIVE', ?, ?, 0, 'DRAFT', 'NOT_CHECKED', ?, ?)",
            it, regNo, ts(t0), ACTOR, ts(t0), ACTOR,
        )
    }

    /** BUSINESS-PROFILE-001: 등록정보 1행(국가 KR, 선택 열 NULL). 생성된 registration_id. */
    fun businessRegistration(c: Connection, businessId: String): Long {
        c.update("INSERT INTO business_registration (business_id, country_code) VALUES (?, 'KR')", businessId)
        return c.prepareStatement("SELECT registration_id FROM business_registration WHERE business_id = ?").use { ps ->
            ps.setString(1, businessId)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /**
     * MERCHANT-CODE-001 MC-1: 정상 그래프의 가맹점은 seq 1 코드 배정을 함께 갖는다(앱 읽기에서 배정 없음 = 결함 — 공유 DB의 다른 조회 시험이 이 행을 읽어도 정상 상태).
     * [withCode] = false는 배정 표 제약 자체를 시험하는 경우만(배정 행을 시험이 직접 넣는다).
     */
    fun merchant(c: Connection, businessId: String, withCode: Boolean = true): String = id("mer").also {
        c.update(
            "INSERT INTO merchant (merchant_id, business_id, display_name, lifecycle, entitlement_revision, created_at, created_by, version) " +
                "VALUES (?, ?, 'Demo merchant', 'ACTIVE', 0, ?, ?, 0)",
            it, businessId, ts(t0), ACTOR,
        )
        if (withCode) {
            c.update(
                "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
                    "VALUES (?, 1, ?, NULL, 'REGISTRATION', NULL, NULL, ?, ?)",
                it, "s" + id("x").substring(2).lowercase().take(13), ts(t0), ACTOR,
            )
        }
    }

    fun site(c: Connection, merchantId: String, identifier: String = channel(), type: String = "WEB"): String = id("site").also {
        c.update(
            "INSERT INTO merchant_site (merchant_site_id, merchant_id, channel_type, channel_identifier, lifecycle, created_at, created_by) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
            it, merchantId, type, identifier, ts(t0), ACTOR,
        )
    }

    fun contract(c: Connection, businessId: String, merchantId: String): String = id("ctr").also {
        c.update(
            "INSERT INTO contract (contract_id, business_id, merchant_id, created_at, created_by, version) VALUES (?, ?, ?, ?, ?, 0)",
            it, businessId, merchantId, ts(t0), ACTOR,
        )
    }

    fun revision(c: Connection, contractId: String, no: Int, businessId: String, scopeMerchantId: String, scopeKind: String = "MERCHANT") {
        c.update(
            "INSERT INTO contract_revision (contract_id, revision_no, business_id, scope_kind, scope_merchant_id, effective_from, " +
                "effective_to, registered_at, registered_by) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
            contractId, no, businessId, scopeKind, scopeMerchantId, ts(t0), ts(t0), ACTOR,
        )
    }

    fun approve(c: Connection, contractId: String, no: Int) {
        c.update(
            "INSERT INTO contract_revision_approval (contract_id, revision_no, approved_at, approved_by) VALUES (?, ?, ?, ?)",
            contractId, no, ts(t0), ACTOR,
        )
    }

    fun agreement(c: Connection, contractId: String): String = id("csa").also {
        c.update(
            "INSERT INTO contract_service_agreement (agreement_id, contract_id, service_kind, added_at, added_by) " +
                "VALUES (?, ?, 'CARD_PG_PAYMENT', ?, ?)",
            it, contractId, ts(t0), ACTOR,
        )
    }

    fun account(c: Connection, merchantId: String, businessId: String, contractId: String, agreementId: String, revisionNo: Int): String =
        id("mid").also {
            c.update(
                "INSERT INTO merchant_account (merchant_account_id, merchant_id, business_id, issuing_contract_id, issuing_agreement_id, " +
                    "issuing_revision_no, lifecycle, issued_at, issued_by, version) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
                it, merchantId, businessId, contractId, agreementId, revisionNo, ts(t0), ACTOR,
            )
        }

    fun designate(c: Connection, merchantId: String, accountId: String) {
        c.update(
            "INSERT INTO default_designation (merchant_id, merchant_account_id, designated_at, designated_by) VALUES (?, ?, ?, ?)",
            merchantId, accountId, ts(t0), ACTOR,
        )
    }

    fun assignment(
        c: Connection,
        accountId: String,
        siteId: String,
        merchantId: String,
        role: String = "PRIMARY",
        from: Instant = t0,
        createdAt: Instant = t0,
        to: Instant? = null,
    ): String = id("asg").also {
        c.update(
            "INSERT INTO merchant_account_site_assignment (assignment_id, merchant_account_id, merchant_site_id, merchant_id, role, status, " +
                "effective_from, effective_to, created_at, created_by) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)",
            it, accountId, siteId, merchantId, role, ts(from), to?.let(::ts), ts(createdAt), ACTOR,
        )
    }

    /** 한 Business에 Merchant 하나·Site 둘·승인된 Revision 1·CSA·MID(PRIMARY 연결·Default)까지 만든 그래프. */
    data class Graph(
        val businessId: String,
        val merchantId: String,
        val siteId: String,
        val secondSiteId: String,
        val contractId: String,
        val agreementId: String,
        val accountId: String,
    )

    fun graph(c: Connection): Graph {
        val business = business(c)
        val merchant = merchant(c, business)
        val site = site(c, merchant)
        val second = site(c, merchant)
        val contract = contract(c, business, merchant)
        revision(c, contract, 1, business, merchant)
        approve(c, contract, 1)
        val agreement = agreement(c, contract)
        val account = account(c, merchant, business, contract, agreement, 1)
        designate(c, merchant, account)
        assignment(c, account, site, merchant)
        return Graph(business, merchant, site, second, contract, agreement, account)
    }
}
