package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.integration.MasterResponseShape
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * MERCHANT-FLOW-001 FLOW-1 ADMIN 섹션: MASTER `ContractIntakeSectionSpec`이 실제 응답 생성기로 고정한 골든 `contract-intake-section-v1.golden.json`의 **ADMIN 바이트 동일 사본**을
 * [MasterResponseShape.CONTRACT_INTAKE_SECTION]으로 읽는다(키 집합 정확 일치 — 배포 경계에서 두 앱이 갈라지지 않게, BusinessShapeGoldenSpec 관례).
 * 골든 값은 Demo 값이다(운영값 아님). 대조군: 키 추가·누락, 모르는 상태, 아직 저장되지 않는 경로 `SALES_AGENCY`(FLOW-4 전 — m-2), 관리자 형식이 아닌 계약 담당자,
 * 근거 참조 형식 밖 문자, 전이 이력 0건, 모르는 희망 서비스(가상계좌 — 선택 불가 값).
 */
class ContractIntakeShapeGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val resource = ContractIntakeShapeGoldenSpec::class.java.getResource("/contracts/contract-intake-section-v1.golden.json")!!
    val golden: JsonNode = mapper.readTree(resource.readText(Charsets.UTF_8))
    val shape = MasterResponseShape.CONTRACT_INTAKE_SECTION
    fun copy(): ObjectNode = golden.deepCopy() as ObjectNode
    fun parses(node: JsonNode): Boolean = runCatching { shape.read(node) }.isSuccess
    fun intake(node: ObjectNode, i: Int): ObjectNode = (node.get("intakes") as ArrayNode).get(i) as ObjectNode
    fun transition(node: ObjectNode, i: Int, t: Int): ObjectNode = (intake(node, i).get("transitions") as ArrayNode).get(t) as ObjectNode

    Given("FLOW-1 the contract-intake section golden produced by MASTER") {
        Then("the ADMIN copy is byte-identical to the MASTER golden (no silent drift between the two apps)") {
            val master = File(System.getProperty("user.dir")).resolve("../master-api/src/test/resources/contracts/contract-intake-section-v1.golden.json")
            master.isFile shouldBe true
            resource.readBytes().toList() shouldBe master.readBytes().toList()
        }
        Then("the golden parses and re-emits the same document (key set and order exact)") {
            val read = shape.read(golden)
            mapper.writeValueAsString(read) shouldBe mapper.writeValueAsString(golden)
        }
        Then("control: one extra or missing key at any level fails (fail-closed)") {
            parses(copy().also { it.put("unexpected", "x") }) shouldBe false
            parses(copy().also { it.remove("businessId") }) shouldBe false
            parses(copy().also { intake(it, 0).put("reason", "사유") }) shouldBe false // '사유 및 비고' 원문은 섹션에 없다(이력 탭)
            parses(copy().also { intake(it, 0).remove("version") }) shouldBe false
            parses(copy().also { transition(it, 1, 1).put("reason", "해지 사유") }) shouldBe false
            parses(copy().also { transition(it, 1, 1).remove("documentRef") }) shouldBe false
        }
        Then("control: unknown status, SALES_AGENCY channel (FLOW-4 전), non-admin contract manager, document ref outside the format, no transitions and an unknown service fail") {
            listOf(
                "status ACTIVE" to copy().also { intake(it, 0).put("status", "ACTIVE") },
                "toStatus APPROVED" to copy().also { transition(it, 0, 0).put("toStatus", "APPROVED") },
                "channel SALES_AGENCY" to copy().also { intake(it, 0).put("intakeChannel", "SALES_AGENCY") },
                "manager system" to copy().also { intake(it, 0).put("contractManager", "system:demo-seed") },
                "manager bare id" to copy().also { intake(it, 0).put("contractManager", "adm_01JC00000000000000000000V1") },
                "document ref space" to copy().also { transition(it, 1, 1).put("documentRef", "DEMO TERM") },
                "no transitions" to copy().also { intake(it, 0).set("transitions", mapper.createArrayNode()) },
                "virtual account service" to copy().also { (intake(it, 0).get("desiredServices") as ArrayNode).add("VIRTUAL_ACCOUNT") },
            ).forEach { (name, node) -> withClue(name) { parses(node) shouldBe false } }
        }
        Then("an empty section (business without intakes) parses") {
            parses(copy().also { it.set("intakes", mapper.createArrayNode()) }) shouldBe true
        }
    }
})
