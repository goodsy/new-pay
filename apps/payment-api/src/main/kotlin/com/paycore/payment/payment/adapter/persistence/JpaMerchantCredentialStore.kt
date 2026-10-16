package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.required.CredentialStatus
import com.paycore.payment.payment.application.required.MerchantCredentialStore
import com.paycore.payment.payment.application.required.StoredMerchantCredential
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [MerchantCredentialStore] JPA 구현(비잠금 읽기, 호출자 TX 안). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantCredentialStore(private val rows: MerchantCredentialJpaRepository) : MerchantCredentialStore {

    override fun find(keyId: CredentialKeyId): StoredMerchantCredential? = translate(ENTITY) {
        rows.findById(keyId.value).orElse(null)?.let { row ->
            StoredMerchantCredential(
                keyId = CredentialKeyId(row.keyId),
                merchantAccountId = MerchantAccountId(row.merchantAccountId),
                environment = Environment.valueOf(row.environment),
                secretSha256Hex = row.secretSha256,
                status = CredentialStatus.valueOf(row.status),
                validFrom = row.validFrom,
                validUntil = row.validUntil,
                leaseUntil = row.leaseUntil,
            )
        }
    }

    private companion object {
        const val ENTITY = "merchant_api_credential"
    }
}
