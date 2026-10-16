package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.MerchantSiteRepository
import com.paycore.master.merchant.domain.MerchantId
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

    private companion object {
        const val ENTITY = "merchant_site"
    }
}
