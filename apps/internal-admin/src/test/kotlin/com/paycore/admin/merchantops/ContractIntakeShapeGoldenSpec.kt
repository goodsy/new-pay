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
 * MERCHANT-FLOW-001 FLOW-2b ADMIN 섹션(§4B.15 ADMIN 행 — golden 새 판): MASTER `ContractIntakeSectionSpec`이 실제 응답 생성기로 고정한 골든 `contract-intake-section-v2.golden.json`의
 * **ADMIN 바이트 동일 사본**을 [MasterResponseShape.CONTRACT_INTAKE_SECTION]으로 읽는다(키 집합 정확 일치 — 배포 경계에서 두 앱이 갈라지지 않게, BusinessShapeGoldenSpec 관례).
 * v1 → v2(기대값 변경 — §4B.15 표 ADMIN `ContractIntakeShapeGoldenSpec` 행, 강화 + 추가형): 접수 건 상태에서 `TERMINATED` 제거(D-GOV-018 ②ⓑ), 접수 건 `kind`·`targetContractSeq`,
 * 최상위 `judgedAt`·`businessContract`(사업자 계약 칸 — 버전·사건) 추가(D-GOV-014 ①). 골든 값은 Demo 값이다(운영값 아님).
 * 대조군: 키 추가·누락(모든 층), 모르는 상태·`TERMINATED` 접수 상태, 아직 저장되지 않는 경로 `SALES_AGENCY`(FLOW-4 전 — m-2), 관리자 형식이 아닌 계약 담당자, 근거 참조 형식 밖 문자,
 * 전이 이력 0건, 모르는 희망 서비스, 모르는 종류·계약 상태·사건 종류·서비스 범위, 버전 0건.
 */
class ContractIntakeShapeGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val resource = ContractIntakeShapeGoldenSpec::class.java.getResource("/contracts/contract-intake-section-v2.golden.json")!!
    val golden: JsonNode = mapper.readTree(resource.readText(Charsets.UTF_8))
    val shape = MasterResponseShape.CONTRACT_INTAKE_SECTION
    fun copy(): ObjectNode = golden.deepCopy() as ObjectNode
    fun parses(node: JsonNode): Boolean = runCatching { shape.read(node) }.isSuccess
    fun intake(node: ObjectNode, i: Int): ObjectNode = (node.get("intakes") as ArrayNode).get(i) as ObjectNode
    fun transition(node: ObjectNode, i: Int, t: Int): ObjectNode = (intake(node, i).get("transitions") as ArrayNode).get(t) as ObjectNode
    fun contract(node: ObjectNode): ObjectNode = node.get("businessContract") as ObjectNode
    fun version(node: ObjectNode, v: Int): ObjectNode = (contract(node).get("versions") as ArrayNode).get(v) as ObjectNode
    fun event(kind: String, evidence: String?): ObjectNode = mapper.createObjectNode().apply {
        put("eventSeq", 1)
        put("kind", kind)
        if (evidence == null) putNull("evidenceRef") else put("evidenceRef", evidence)
        put("actorRef", "admin:adm_01JC00000000000000000000V1")
        put("occurredAt", "2026-11-26T02:00:00.000Z")
    }

    Given("FLOW-2b the contract-intake section golden v2 produced by MASTER") {
        Then("the ADMIN copy is byte-identical to the MASTER golden v2 (no silent drift between the two apps); the v1 copy is gone") {
            val master = File(System.getProperty("user.dir")).resolve("../master-api/src/test/resources/contracts/contract-intake-section-v2.golden.json")
            master.isFile shouldBe true
            resource.readBytes().toList() shouldBe master.readBytes().toList()
            ContractIntakeShapeGoldenSpec::class.java.getResource("/contracts/contract-intake-section-v1.golden.json") shouldBe null
        }
        Then("the golden parses and re-emits the same document (key set and order exact)") {
            val read = shape.read(golden)
            mapper.writeValueAsString(read) shouldBe mapper.writeValueAsString(golden)
        }
        Then("control: one extra or missing key at any level fails (fail-closed)") {
            parses(copy().also { it.put("unexpected", "x") }) shouldBe false
            parses(copy().also { it.remove("businessId") }) shouldBe false
            parses(copy().also { it.remove("judgedAt") }) shouldBe false
            parses(copy().also { it.remove("businessContract") }) shouldBe false
            parses(copy().also { intake(it, 0).put("reason", "사유") }) shouldBe false // '사유 및 비고' 원문은 섹션에 없다(이력 탭)
            parses(copy().also { intake(it, 0).remove("version") }) shouldBe false
            parses(copy().also { intake(it, 0).remove("kind") }) shouldBe false
            parses(copy().also { intake(it, 0).remove("targetContractSeq") }) shouldBe false
            parses(copy().also { transition(it, 0, 2).put("reason", "사유") }) shouldBe false
            parses(copy().also { transition(it, 0, 2).remove("documentRef") }) shouldBe false
            parses(copy().also { contract(it).put("reason", "사유") }) shouldBe false
            parses(copy().also { contract(it).remove("linkedMerchantCount") }) shouldBe false
            parses(copy().also { version(it, 1).put("reason", "사유") }) shouldBe false
            parses(copy().also { version(it, 1).remove("createdAt") }) shouldBe false // MINOR-4: 버전 기록 시각은 필수
            parses(copy().also { (contract(it).get("events") as ArrayNode).add(event("TERMINATE", null).put("reason", "해지 사유")) }) shouldBe false
        }
        Then("control: TERMINATED intake status is gone (D-GOV-018 ②ⓑ), and unknown status/kind/contract status/event kind/service, SALES_AGENCY, non-admin manager, bad refs, no transitions, no versions fail") {
            listOf(
                "intake status TERMINATED" to copy().also { intake(it, 1).put("status", "TERMINATED") },
                "toStatus TERMINATED" to copy().also { transition(it, 0, 2).put("toStatus", "TERMINATED") },
                "status ACTIVE" to copy().also { intake(it, 0).put("status", "ACTIVE") },
                "toStatus APPROVED" to copy().also { transition(it, 0, 0).put("toStatus", "APPROVED") },
                "kind RENEWAL" to copy().also { intake(it, 0).put("kind", "RENEWAL") },
                "channel SALES_AGENCY" to copy().also { intake(it, 0).put("intakeChannel", "SALES_AGENCY") },
                // 기대값 변경(FLOW-3 Coordinator 결정(가역) — Demo seed 담당자 허용): `system:demo-seed` **정확히 그 값만** 허용으로 바뀌었다(아래 Then). 다른 system:* 와
                // 비슷한 값은 여전히 거부 — 대조군을 늘렸다(완화는 그 한 값뿐).
                "manager system:migration" to copy().also { intake(it, 0).put("contractManager", "system:migration") },
                "manager system:demo-seed2" to copy().also { intake(it, 0).put("contractManager", "system:demo-seed2") },
                "manager system:demo-seed with space" to copy().also { intake(it, 0).put("contractManager", "system:demo-seed ") },
                "manager SYSTEM:DEMO-SEED" to copy().also { intake(it, 0).put("contractManager", "SYSTEM:DEMO-SEED") },
                "manager admin:demo-seed" to copy().also { intake(it, 0).put("contractManager", "admin:demo-seed") },
                "manager bare id" to copy().also { intake(it, 0).put("contractManager", "adm_01JC00000000000000000000V1") },
                "document ref space" to copy().also { transition(it, 0, 2).put("documentRef", "DEMO DOC") },
                "version document ref space" to copy().also { version(it, 1).put("documentRef", "DEMO DOC") },
                "no transitions" to copy().also { intake(it, 0).set("transitions", mapper.createArrayNode()) },
                "virtual account service" to copy().also { (intake(it, 0).get("desiredServices") as ArrayNode).add("VIRTUAL_ACCOUNT") },
                "contract status APPROVED" to copy().also { contract(it).put("status", "APPROVED") },
                "contract seq 0" to copy().also { contract(it).put("contractSeq", 0) },
                "no versions" to copy().also { contract(it).set("versions", mapper.createArrayNode()) },
                "scope CARD_KEYIN" to copy().also { (version(it, 1).get("services") as ArrayNode).set(0, "CARD_KEYIN") },
                "empty scope" to copy().also { version(it, 1).set("services", mapper.createArrayNode()) },
                "event kind CLOSE" to copy().also { (contract(it).get("events") as ArrayNode).add(event("CLOSE", null)) },
                "event evidence space" to copy().also { (contract(it).get("events") as ArrayNode).add(event("TERMINATE", "DEMO TERM")) },
            ).forEach { (name, node) -> withClue(name) { parses(node) shouldBe false } }
        }
        Then("FLOW-3 Coordinator 결정: a Demo-seed intake (contract manager exactly system:demo-seed — fresh Demo without a migrated contract) parses") {
            val node = copy().also { intake(it, 0).put("contractManager", "system:demo-seed") }
            parses(node) shouldBe true
        }
        Then("an empty section (business without contract nor intakes) parses; every contract status and a TERMINATE event with evidence parse") {
            parses(copy().also { it.set("intakes", mapper.createArrayNode()); it.putNull("businessContract") }) shouldBe true
            listOf("NOT_YET_EFFECTIVE", "ACTIVE", "SUSPENDED", "TERMINATED", "EXPIRED").forEach { status ->
                withClue(status) { parses(copy().also { contract(it).put("status", status) }) shouldBe true }
            }
            parses(copy().also { (contract(it).get("events") as ArrayNode).add(event("TERMINATE", "DEMO-TERM-0001")) }) shouldBe true
            parses(copy().also { contract(it).putNull("applyingVersionNo"); contract(it).putNull("scheduledVersionNo") }) shouldBe true
        }
    }
})
