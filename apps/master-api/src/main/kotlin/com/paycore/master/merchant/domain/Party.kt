package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 국내 사업자등록번호(D-MD-008: Business는 이 번호로 하나). 표기는 10자리 숫자만 받는다 — 하이픈 등 다른 표기를 암묵 정규화하지 않고
 * 거절해 같은 번호가 다른 문자열로 중복 등록되는 일을 막는다(fail-closed, 관례). 노출 통제(D-MD-008): [toString]·[masked]는 앞 3자리만
 * 보여 주고 나머지는 가린다. 로그·이벤트·이력·예외 메시지에 평문을 넣지 않는다. 검증번호(체크섬) 검사는 하지 않는다(형식만, 관례).
 */
@JvmInline
value class BusinessRegistrationNumber(val value: String) {
    init {
        require(value.length == LENGTH && value.all { it in '0'..'9' }) { "business registration number must be $LENGTH ASCII digits" }
    }

    /** 감사·화면용 가린 값(앞 3자리 + `*`). */
    fun masked(): String = value.take(VISIBLE) + "*".repeat(LENGTH - VISIBLE)

    override fun toString(): String = "BusinessRegistrationNumber(${masked()})"

    private companion object {
        const val LENGTH = 10
        const val VISIBLE = 3
    }
}

/**
 * 생명주기(D-MD-006): ACTIVE / TERMINATED(비가역). 신규 결제 중지는 생명주기가 아니라 별도 차단 기록이다(사유·행위자, 여럿 독립 —
 * MASTER-LIFECYCLE-001). 종료 명령은 이번 범위가 아니라 전이 메서드가 없다.
 */
enum class Lifecycle { ACTIVE, TERMINATED }

/** 법적 사업 주체. 하나의 Business가 복수 Merchant를 가질 수 있다(초기). 계약 당사자(D-MD-004). */
class Business private constructor(
    val id: BusinessId,
    val registrationNumber: BusinessRegistrationNumber,
    val displayName: DisplayName,
    val lifecycle: Lifecycle,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.BUSINESS_NOT_ACTIVE)
    }

    override fun toString(): String = "Business(id=${id.value}, registrationNumber=${registrationNumber.masked()}, lifecycle=$lifecycle)"

    companion object {
        fun register(id: BusinessId, registrationNumber: BusinessRegistrationNumber, displayName: DisplayName, at: Instant, by: ActorRef) =
            Business(id, registrationNumber, displayName, Lifecycle.ACTIVE, at, by)

        /** persistence Mapper 전용 복원. */
        fun reconstitute(
            id: BusinessId,
            registrationNumber: BusinessRegistrationNumber,
            displayName: DisplayName,
            lifecycle: Lifecycle,
            createdAt: Instant,
            createdBy: ActorRef,
        ) = Business(id, registrationNumber, displayName, lifecycle, createdAt, createdBy)
    }
}

/**
 * 가맹점. Business 1:N, Merchant 1:N MerchantSite·MID(구조 §13). Merchant 안 관계 변경(최초 Default 발급·개통 등)의 직렬화 지점이다
 * (application이 이 행을 먼저 잠근다).
 *
 * [entitlementRevision]: 결제 허용 판정 입력(MID 발급·Site 연결·개통·계약 승인)이 바뀔 때마다 +1하는 단조 값(관례 — MASTER-SNAPSHOT-001이
 * 차단 버전을 과거 허용 이벤트로 되돌리지 않도록 버전 비교에 쓴다, 허용 §4.2). JPA version(동시 수정 감지 기술 값)과 별개다.
 */
class Merchant private constructor(
    val id: MerchantId,
    val businessId: BusinessId,
    val displayName: DisplayName,
    val lifecycle: Lifecycle,
    entitlementRevision: Long,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    var entitlementRevision: Long = entitlementRevision
        private set

    init {
        require(entitlementRevision >= 0) { "entitlementRevision must not be negative" }
    }

    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.MERCHANT_NOT_ACTIVE)
    }

    /** 결제 허용 판정 입력이 바뀌었다(같은 TX에서 저장). */
    fun recordEntitlementChange() {
        entitlementRevision += 1
    }

    override fun toString(): String = "Merchant(id=${id.value}, lifecycle=$lifecycle, entitlementRevision=$entitlementRevision)"

    companion object {
        fun register(id: MerchantId, business: Business, displayName: DisplayName, at: Instant, by: ActorRef): Merchant {
            business.requireActive()
            return Merchant(id, business.id, displayName, Lifecycle.ACTIVE, 0, at, by)
        }

        fun reconstitute(
            id: MerchantId,
            businessId: BusinessId,
            displayName: DisplayName,
            lifecycle: Lifecycle,
            entitlementRevision: Long,
            createdAt: Instant,
            createdBy: ActorRef,
        ) = Merchant(id, businessId, displayName, lifecycle, entitlementRevision, createdAt, createdBy)
    }
}

enum class ChannelType { WEB, APP }

/**
 * Site 채널 식별값. 중복 판정(D-MD-008)을 위해 **소문자 정규형만** 받는다(D-MD-009 — 자동 정규화 없이 대문자·포트 등 다른 표기는 거절,
 * `www.` 등 서브도메인은 다른 채널). WEB = 도메인 host 이름만(scheme·port·path 없음, 라벨 1~63자, 전체 253자 이하, **IP 리터럴 거절** —
 * D-MD-014: 마지막 라벨이 10진 숫자이거나 `0x` 16진수면 WHATWG 기준 IPv4로 해석되므로 거절, IPv6는 `:`가 있어 형식에서 거절), APP = 점으로 구분한 앱 식별자.
 */
data class SiteChannel(val type: ChannelType, val identifier: String) {
    init {
        when (type) {
            ChannelType.WEB -> require(
                identifier.length <= MAX_LENGTH && WEB_HOST.matches(identifier) && !NUMERIC_LAST_LABEL.matches(identifier.substringAfterLast('.')),
            ) {
                "WEB channel identifier must be a lower-case domain host name (no IP literal)"
            }
            ChannelType.APP -> require(identifier.length <= MAX_LENGTH && APP_ID.matches(identifier)) {
                "APP channel identifier must be a lower-case dotted application id"
            }
        }
    }

    private companion object {
        const val MAX_LENGTH = 253
        val WEB_HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
        val APP_ID = Regex("^[a-z0-9][a-z0-9_]*(\\.[a-z0-9_]+)+$")

        /**
         * WHATWG URL "ends in a number": 마지막 라벨이 10진 숫자이거나 `0x` + 16진수(빈 값 포함)면 브라우저가 IPv4로 해석한다(review n-2) — 도메인이 아니다.
         */
        val NUMERIC_LAST_LABEL = Regex("^([0-9]+|0x[0-9a-f]*)$")
    }
}

/** 결제 채널(웹·앱). Merchant 1:N. 같은 Merchant 소속 MID와만 연결된다(구조 §8). */
class MerchantSite private constructor(
    val id: MerchantSiteId,
    val merchantId: MerchantId,
    val channel: SiteChannel,
    val lifecycle: Lifecycle,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.SITE_NOT_ACTIVE)
    }

    override fun toString(): String = "MerchantSite(id=${id.value}, merchantId=${merchantId.value}, lifecycle=$lifecycle)"

    companion object {
        fun register(id: MerchantSiteId, merchant: Merchant, channel: SiteChannel, at: Instant, by: ActorRef): MerchantSite {
            merchant.requireActive()
            return MerchantSite(id, merchant.id, channel, Lifecycle.ACTIVE, at, by)
        }

        fun reconstitute(
            id: MerchantSiteId,
            merchantId: MerchantId,
            channel: SiteChannel,
            lifecycle: Lifecycle,
            createdAt: Instant,
            createdBy: ActorRef,
        ) = MerchantSite(id, merchantId, channel, lifecycle, createdAt, createdBy)
    }
}
