package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * BIZ-CRIT-APPROVAL-001 S2 리뷰 MINOR-4: 근거 없는 사업자 수정의 요청 지문 입력(필드 목록·정규 바이트)을 리터럴로 고정한다 — S2 전 저장된 `admin_request` 행과 같은 바이트(재생 호환).
 * 근거가 있으면 필드가 **정확히 하나** 끝에 붙는다(같은 키·다른 근거 = 다른 지문 = 409). 기대값은 리터럴(구현과 독립 — 바꾸면 기존 행 재생이 깨진다).
 */
class BusinessCommandFingerprintGoldenSpec : FunSpec({

    fun command(evidence: String? = null) = UpdateBusinessCommand(
        OnboardingFixture.request(), BusinessId("biz_01JC0000000000000000000001"), 3, BusinessChangeReason("사유"), BusinessType.INDIVIDUAL, DisplayName("상호"),
        BusinessFixtures.individualProfile(), evidence?.let(::EvidenceRef),
    )

    test("without evidence: the field list and canonical bytes are exactly the pre-S2 ones") {
        val fields = BusinessCommandFingerprintFields.of(command())
        fields shouldBe listOf(
            "biz_01JC0000000000000000000001", "3", "사유", "INDIVIDUAL", "상호", null, "2026-01-01", "KR", null, "0212345678", "office@example.test",
            "1", "홍길동", "SOLE", "2", "BUSINESS_TYPE", "서비스업", "BUSINESS_ITEM", "전자상거래", "1", "REGISTERED", "06236", "서울특별시 강남구 테헤란로 1", "101호",
            "1", "김담당", "contact@example.test", "01012345678", "true",
        )
        String(AdminCommandExecutor.canonicalOf(AdminOperation.UPDATE_BUSINESS, fields), Charsets.UTF_8) shouldBe
            "14:UpdateBusiness|30:biz_01JC0000000000000000000001|1:3|6:사유|10:INDIVIDUAL|6:상호|~|10:2026-01-01|2:KR|~|10:0212345678|19:office@example.test|1:1|" +
            "9:홍길동|4:SOLE|1:2|13:BUSINESS_TYPE|12:서비스업|13:BUSINESS_ITEM|15:전자상거래|1:1|10:REGISTERED|5:06236|40:서울특별시 강남구 테헤란로 1|6:101호|1:1|9:김담당|" +
            "20:contact@example.test|11:01012345678|4:true|"
    }

    test("with evidence: exactly one more field (the evidence reference) at the end, the prefix unchanged") {
        val without = BusinessCommandFingerprintFields.of(command())
        val with = BusinessCommandFingerprintFields.of(command("DOC-1"))
        listOf(with.size - without.size, with.dropLast(1) == without, with.last()) shouldBe listOf(1, true, "DOC-1")
    }
})
