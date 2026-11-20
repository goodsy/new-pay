package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.MerchantSiteRepository
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.SiteCode
import java.time.Instant
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.SiteChannel
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantSiteRepository(private val sites: MerchantSiteJpaRepository, private val entityManager: EntityManager) : MerchantSiteRepository {

    override fun insert(site: MerchantSite): Unit = translate(ENTITY) {
        entityManager.persist(MasterPersistenceMapper.siteRow(site))
        entityManager.flush()
    }

    override fun find(id: MerchantSiteId): MerchantSite? = translate(ENTITY) {
        entityManager.find(JpaMerchantSiteEntity::class.java, id.value)?.let(MasterPersistenceMapper::toSite)
    }

    override fun findActiveByChannel(channel: SiteChannel): MerchantSiteId? = translate(ENTITY) {
        sites.findActiveByChannel(channel.type.name, channel.identifier)?.let(::MerchantSiteId)
    }

    override fun listByMerchant(merchantId: MerchantId): List<MerchantSite> = translate(ENTITY) {
        sites.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toSite)
    }

    /**
     * SITE-CONTRACT-CODE-001 §3.4·§7 주의 ②: JPQL `max`는 영속성 컨텍스트 캐시가 아니라 DB를 읽는다. 호출 순서(merchant `lockRow` 뒤·`beginWrites` 전)가 직렬화·판정 근거다 —
     * 명령 TX가 READ COMMITTED라 잠금 뒤 읽는 일반 SELECT가 앞선 같은 가맹점 등록의 커밋을 본다. 코드 표 FOR UPDATE는 권한상 불가(1142)라 쓰지 않는다(C-13).
     */
    override fun nextSiteCodeSeq(merchantId: MerchantId): Int = translate(CODE_ENTITY) {
        val max = entityManager.createQuery(
            "select max(c.codeSeq) from JpaMerchantSiteCodeEntity c where c.merchantId = :merchantId", Int::class.javaObjectType,
        ).setParameter("merchantId", merchantId.value).singleResult
        (max ?: 0) + 1
    }

    /** 주의 ①·④: 독립 persist(연관 매핑 없음) + 같은 블록 flush — Site 행([insert]가 이미 flush)이 먼저라 FK 만족, 1062는 여기서 `PersistenceFailures`가 결함 분류(C-3). */
    override fun insertCode(siteId: MerchantSiteId, merchantId: MerchantId, code: SiteCode, assignedAt: Instant, assignedBy: ActorRef): Unit = translate(CODE_ENTITY) {
        entityManager.persist(MasterPersistenceMapper.siteCodeRow(siteId, merchantId, code, assignedAt, assignedBy))
        entityManager.flush()
    }

    override fun findCode(siteId: MerchantSiteId): SiteCode? = translate(CODE_ENTITY) {
        entityManager.find(JpaMerchantSiteCodeEntity::class.java, siteId.value)?.let(MasterPersistenceMapper::toSiteCode)
    }

    private companion object {
        const val ENTITY = "merchant_site"
        const val CODE_ENTITY = "merchant_site_code"
    }
}
