package com.paycore.financial.acquiring.adapter.integration

import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.domain.AcquisitionContractViolation
import com.paycore.financial.acquiring.domain.AcquisitionFileHeader
import com.paycore.financial.acquiring.domain.CutoffPolicy
import com.paycore.financial.acquiring.domain.CutoffPolicyVersion
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.LocalDate
import java.time.LocalTime

/**
 * 매입 설정(ACQUIRING-001 — 운영 기본값 없음: 값이 없거나 형식 밖이면 기동 실패). 마감 정책은 버전 관리 설정이며 **Demo 값**이다(D-ACQ-003 — 운영값 아님, LIVE Gate).
 * 업무일 = Asia/Seoul 달력일(Q-CALENDAR 전 Demo 한정). 이 클래스는 배치2에서 매입사별 SFTP 접속 설정(D-ACQ-006 — 키는 파일 경로로만)도 받는다.
 *
 * @param senderId 파일 헤더 송신자 ID(`[A-Z0-9]{1,16}`, Demo).
 * @param cutoffPolicies 마감 정책 버전 목록(1개 이상, 버전·시행일 유일). 각 `{version, effective-from(yyyy-MM-dd), cutoff-time(HH:mm, Asia/Seoul)}`.
 */
@ConfigurationProperties(prefix = "paycore.financial.acquiring")
class AcquiringSettings(senderId: String, cutoffPolicies: List<CutoffPolicyEntry>) : AcquisitionPolicySource {

    class CutoffPolicyEntry(val version: String, val effectiveFrom: LocalDate, val cutoffTime: LocalTime)

    override val senderId: String = senderId.also {
        require(AcquisitionFileHeader.SENDER.matches(it)) { "paycore.financial.acquiring.sender-id must be 1..16 characters of [A-Z0-9]" }
    }

    override val cutoffPolicy: CutoffPolicy = try {
        CutoffPolicy(cutoffPolicies.map { CutoffPolicyVersion(it.version, it.effectiveFrom, it.cutoffTime) })
    } catch (e: AcquisitionContractViolation) {
        throw IllegalArgumentException("paycore.financial.acquiring.cutoff-policies: ${e.message}")
    }

    override fun toString(): String = "AcquiringSettings(senderId=$senderId, cutoffPolicies=${cutoffPolicy.versions})"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AcquiringSettings::class)
class AcquiringConfiguration
