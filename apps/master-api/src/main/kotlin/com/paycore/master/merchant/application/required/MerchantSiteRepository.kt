package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.SiteChannel
import com.paycore.master.merchant.domain.SiteCode
import java.time.Instant

interface MerchantSiteRepository {
    fun insert(site: MerchantSite)

    fun find(id: MerchantSiteId): MerchantSite?

    /** 같은 채널 식별값의 활성 Site(어느 Merchant든, D-MD-008). 잠금 없음 — 경합은 UNIQUE 충돌 → 재판정. */
    fun findActiveByChannel(channel: SiteChannel): MerchantSiteId?

    fun listByMerchant(merchantId: MerchantId): List<MerchantSite>

    /**
     * SITE-CONTRACT-CODE-001 §3.4(C-12·C-13 — 등록 경로 전용): 그 가맹점의 다음 Site 코드 순번 = `MAX(code_seq) + 1`(코드 행 없으면 1 — 종료 Site 포함, 재사용 없음).
     * **merchant 행 잠금 뒤**, `beginWrites()` **전**에 부른다(명령 TX READ COMMITTED — 잠금 뒤 일반 SELECT가 최신 커밋을 본다. 코드 표 FOR UPDATE는 권한상 불가·미사용).
     */
    fun nextSiteCodeSeq(merchantId: MerchantId): Int

    /** 코드 행 INSERT(append-only, source REGISTRATION). Site 행([insert])이 먼저 flush된 뒤 같은 TX에서 부른다(FK `fk_msc_site`). 1062는 결함 분류(C-3). */
    fun insertCode(siteId: MerchantSiteId, merchantId: MerchantId, code: SiteCode, assignedAt: Instant, assignedBy: ActorRef)

    /** 명령 응답·재생용 코드 조회(저장 reference → 코드, C-4). 없으면 null(호출자가 결함으로 본다). 상세·라벨 화면 조회는 jOOQ Reader가 맡는다. */
    fun findCode(siteId: MerchantSiteId): SiteCode?
}
