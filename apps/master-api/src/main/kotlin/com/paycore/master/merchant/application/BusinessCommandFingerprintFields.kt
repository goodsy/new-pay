package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.CompleteBusinessRegistrationCommand
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.BusinessProfile

/**
 * 사업자 명령의 멱등 지문 필드 평탄화(BUSINESS-PROFILE-001 §6.7·C-9 — `AdminCommandExecutor.fingerprintOf`의 canonical 입력). 고정 순서:
 * 공통(멱등키·행위자·상관 ID) 제외 명령 필드 → 등록정보 6필드 → 목록마다 **개수** + 행별 필드(요청 순서 그대로). null은 그대로 null(`~`).
 * 개수 접두라 목록 경계가 모호하지 않다(대표자 1행 vs 2행이 같은 연결 문자열이 되지 않는다). 같은 내용·다른 목록 순서는 다른 지문(같은 키 → 409).
 * 이 입력에는 번호·개인정보가 들어간다 — SEC-BIZ-001(D-MOPS-010 ②·D-GOV-001 ②)부터 저장 지문은 canonical 전체의 MASTER 비밀 키 HMAC(`h1:`)이라 키 없이 DB 행만으로 대입 확인을
 * 할 수 없다(L-13 해소 — 키 보관·교체는 LIVE Gate L-23). HMAC 전 `m1:` 행은 Demo에서만 호환 비교(L-25). 지문은 로그·응답에 나가지 않는다.
 */
internal object BusinessCommandFingerprintFields {

    fun of(command: CreateBusinessCommand): List<String?> =
        listOf(command.registrationStatus.name, command.businessType.name, command.registrationNumber.value, command.displayName.value) + profile(command.profile)

    fun of(command: UpdateBusinessCommand): List<String?> =
        listOf(command.businessId.value, command.version.toString(), command.reason?.value, command.businessType.name, command.displayName.value) + profile(command.profile)

    fun of(command: CompleteBusinessRegistrationCommand): List<String?> =
        listOf(command.businessId.value, command.version.toString(), command.reason?.value, command.businessType.name, command.displayName.value) + profile(command.profile)

    private fun profile(p: BusinessProfile): List<String?> = buildList {
        val r = p.registration
        add(r.corporateRegistrationNumber?.value)
        add(r.openingDate?.toString())
        add(r.countryCode)
        add(r.englishName?.value)
        add(r.phoneNumber?.value)
        add(r.email?.value)
        add(p.representatives.size.toString())
        p.representatives.forEach { add(it.name.value); add(it.type.name) }
        add(p.categories.size.toString())
        p.categories.forEach { add(it.type.name); add(it.name.value) }
        add(p.addresses.size.toString())
        p.addresses.forEach { add(it.type.name); add(it.postalCode.value); add(it.baseAddress.value); add(it.detailAddress?.value) }
        add(p.contacts.size.toString())
        p.contacts.forEach { add(it.name.value); add(it.email.value); add(it.phoneNumber.value); add(it.primary.toString()) }
    }
}