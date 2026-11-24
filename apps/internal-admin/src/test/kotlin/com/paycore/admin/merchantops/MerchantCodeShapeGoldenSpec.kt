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
 * MERCHANT-CODE-001 MC-1-9 ADMIN 쪽(C-22 — MC-1 배포 단위가 ADMIN을 깨지 않음): MASTER `MerchantOpsShapeGoldenSpec`이 실제 응답 생성기로 만든 바이트와 같은 golden
 * `merchant-ops-response-shapes-v2.golden.json`(이 모듈 사본 — MASTER 원본과 바이트 동일 단언)을 `MasterResponseShape`로 해석한다: 목록 → `DIRECTORY`, 코드 검색 → 새 `DIRECTORY_BY_CODE`,
 * 상세 → `DETAIL` 모두 성공. 대조군: 목록에 `matchedRetiredCode`가 오면 `DIRECTORY` 해석 실패, 키 하나 빠지면 실패, 코드 형식 위반·이력 3개 실패.
 * TXN-LIST-ENRICH-001 T-E2-10(N-2): 라벨 `accountLabels` → `MERCHANT_ACCOUNT_LABELS` 성공(MASTER 원본과 함께 갱신 — 위 바이트 동일 단언), 대조군: 내부 키 추가·키 누락 실패.
 */
class MerchantCodeShapeGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val resource = MerchantCodeShapeGoldenSpec::class.java.getResource("/contracts/merchant-ops-response-shapes-v2.golden.json")!!
    val golden: JsonNode = mapper.readTree(resource.readText(Charsets.UTF_8))
    fun copy(name: String): ObjectNode = golden.get(name).deepCopy() as ObjectNode
    fun parses(shape: MasterResponseShape, node: JsonNode): Boolean = runCatching { shape.read(node) }.isSuccess
    fun firstItem(node: ObjectNode): ObjectNode = (node.get("items") as ArrayNode).get(0) as ObjectNode

    Given("MC-1-9 the cross-batch golden produced by MASTER") {
        Then("MC-1-9 the ADMIN copy is byte-identical to the MASTER golden (no silent drift between the two apps)") {
            val master = File(System.getProperty("user.dir")).resolve("../master-api/src/test/resources/contracts/merchant-ops-response-shapes-v2.golden.json")
            master.isFile shouldBe true
            resource.readBytes().toList() shouldBe master.readBytes().toList()
        }

        Then("MC-1-9 list → DIRECTORY, code search → DIRECTORY_BY_CODE, detail → DETAIL all parse, carrying the code fields") {
            val list = MasterResponseShape.DIRECTORY.read(golden.get("list")) as Map<*, *>
            ((list["items"] as List<*>).single() as Map<*, *>)["merchantCode"] shouldBe "shopabcd12"
            val byCode = MasterResponseShape.DIRECTORY_BY_CODE.read(golden.get("codeSearch")) as Map<*, *>
            ((byCode["items"] as List<*>).single() as Map<*, *>).let { listOf(it["merchantCode"], it["matchedRetiredCode"]) } shouldBe listOf("shopxyz789", true)
            val detail = MasterResponseShape.DETAIL.read(golden.get("detail")) as Map<*, *>
            (detail["merchant"] as Map<*, *>)["merchantCode"] shouldBe "shopxyz789"
            (detail["merchantCodeHistory"] as List<*>).size shouldBe 2
            detail["merchantCodeChangeable"] shouldBe false
        }

        Then("MC-1-9 control: matchedRetiredCode in a list response fails DIRECTORY; the code search body fails DIRECTORY and the list body fails DIRECTORY_BY_CODE") {
            val listWithRetired = copy("list").also { firstItem(it).put("matchedRetiredCode", false) }
            parses(MasterResponseShape.DIRECTORY, listWithRetired) shouldBe false
            parses(MasterResponseShape.DIRECTORY, golden.get("codeSearch")) shouldBe false
            parses(MasterResponseShape.DIRECTORY_BY_CODE, golden.get("list")) shouldBe false
        }

        Then("MC-1-9 control: one missing key fails (list merchantCode, detail merchant.merchantCode / merchantCodeHistory / merchantCodeChangeable)") {
            parses(MasterResponseShape.DIRECTORY, copy("list").also { firstItem(it).remove("merchantCode") }) shouldBe false
            parses(MasterResponseShape.DIRECTORY_BY_CODE, copy("codeSearch").also { firstItem(it).remove("matchedRetiredCode") }) shouldBe false
            parses(MasterResponseShape.DETAIL, copy("detail").also { (it.get("merchant") as ObjectNode).remove("merchantCode") }) shouldBe false
            parses(MasterResponseShape.DETAIL, copy("detail").also { it.remove("merchantCodeHistory") }) shouldBe false
            parses(MasterResponseShape.DETAIL, copy("detail").also { it.remove("merchantCodeChangeable") }) shouldBe false
            parses(MasterResponseShape.DETAIL, copy("detail").also { (it.get("merchant") as ObjectNode).put("registeredBy", "admin:adm_01JC00000000000000000000V1") }) shouldBe false
        }

        Then("T-E2-10 account labels → MERCHANT_ACCOUNT_LABELS parses (3 keys per item); an internal key or a missing key fails") {
            val labels = MasterResponseShape.MERCHANT_ACCOUNT_LABELS.read(golden.get("accountLabels")) as Map<*, *>
            (labels["items"] as List<*>).map { (it as Map<*, *>).values.toList() } shouldBe listOf(
                listOf("mid_01JC0000000000000000000001", "데모상점", "demoshop01"), listOf("mid_01JC0000000000000000000002", "Demo Merchant", "shopxyz789"),
            )
            parses(MasterResponseShape.MERCHANT_ACCOUNT_LABELS, copy("accountLabels").also { firstItem(it).put("merchantId", "mer_01JC0000000000000000000001") }) shouldBe false
            parses(MasterResponseShape.MERCHANT_ACCOUNT_LABELS, copy("accountLabels").also { firstItem(it).remove("merchantCode") }) shouldBe false
            parses(MasterResponseShape.MERCHANT_ACCOUNT_LABELS, golden.get("list")) shouldBe false
        }

        Then("MC-3-17 merchant labels → MERCHANT_LABELS parses (3 keys per item); a business key, lifecycle, a missing key, a MID key or the account-label body fails") {
            val labels = MasterResponseShape.MERCHANT_LABELS.read(golden.get("merchantLabels")) as Map<*, *>
            (labels["items"] as List<*>).map { (it as Map<*, *>).values.toList() } shouldBe listOf(
                listOf("mer_01JC0000000000000000000001", "데모상점", "demoshop01"), listOf("mer_01JC0000000000000000000002", "Demo Merchant", "shopxyz789"),
            )
            parses(MasterResponseShape.MERCHANT_LABELS, copy("merchantLabels").also { firstItem(it).put("businessId", "biz_01JC0000000000000000000001") }) shouldBe false
            parses(MasterResponseShape.MERCHANT_LABELS, copy("merchantLabels").also { firstItem(it).put("lifecycle", "ACTIVE") }) shouldBe false
            parses(MasterResponseShape.MERCHANT_LABELS, copy("merchantLabels").also { firstItem(it).remove("merchantCode") }) shouldBe false
            parses(MasterResponseShape.MERCHANT_LABELS, copy("merchantLabels").also { firstItem(it).put("merchantId", "mid_01JC0000000000000000000001") }) shouldBe false
            parses(MasterResponseShape.MERCHANT_LABELS, copy("merchantLabels").also { firstItem(it).put("merchantCode", "DEMOSHOP01") }) shouldBe false
            parses(MasterResponseShape.MERCHANT_LABELS, golden.get("accountLabels")) shouldBe false
            parses(MasterResponseShape.MERCHANT_ACCOUNT_LABELS, golden.get("merchantLabels")) shouldBe false
        }

        Then("BRN-UNMASK-001 list/code search/detail carry the raw 10-digit number (D-BIZ-007); the old masked value or a hyphenated value fails") {
            // M-1(v2 계약): 옛 v1 키 registrationNumberMasked는 이름을 바꿔 와도(옛 MASTER) 해석 불가.
            fun renamed(node: ObjectNode) = node.also { it.put("registrationNumberMasked", it.get("registrationNumber").asString()); it.remove("registrationNumber") }
            parses(MasterResponseShape.DIRECTORY, copy("list").also { renamed(firstItem(it)) }) shouldBe false
            parses(MasterResponseShape.DIRECTORY_BY_CODE, copy("codeSearch").also { renamed(firstItem(it)) }) shouldBe false
            parses(MasterResponseShape.DETAIL, copy("detail").also { renamed(it.get("business") as ObjectNode) }) shouldBe false
            // 기대값 변경(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①): 가맹점 목록·상세의 사업자등록번호는 원문 10자리.
            golden.get("list").get("items").get(0).get("registrationNumber").asString() shouldBe "7780000001"
            golden.get("detail").get("business").get("registrationNumber").asString() shouldBe "7780000001"
            listOf("778*******", "778-00-00001", "778000000").forEach { v ->
                withClue(v) {
                    parses(MasterResponseShape.DIRECTORY, copy("list").also { firstItem(it).put("registrationNumber", v) }) shouldBe false
                    parses(MasterResponseShape.DIRECTORY_BY_CODE, copy("codeSearch").also { firstItem(it).put("registrationNumber", v) }) shouldBe false
                    parses(MasterResponseShape.DETAIL, copy("detail").also { (it.get("business") as ObjectNode).put("registrationNumber", v) }) shouldBe false
                }
            }
        }

        Then("MC-1-9 control: a code outside ^[a-z][a-z0-9]{5,13}$, an unknown source and a third history row fail") {
            parses(MasterResponseShape.DIRECTORY, copy("list").also { firstItem(it).put("merchantCode", "SHOPABCD12") }) shouldBe false
            parses(MasterResponseShape.DIRECTORY, copy("list").also { firstItem(it).put("merchantCode", "mer_01JC0000000000000000000001") }) shouldBe false
            val history = { d: ObjectNode -> d.get("merchantCodeHistory") as ArrayNode }
            parses(MasterResponseShape.DETAIL, copy("detail").also { (history(it).get(0) as ObjectNode).put("source", "IMPORT") }) shouldBe false
            parses(MasterResponseShape.DETAIL, copy("detail").also { history(it).add(history(it).get(1).deepCopy()) }) shouldBe false
            parses(MasterResponseShape.DIRECTORY_BY_CODE, copy("codeSearch").also { (it.get("items") as ArrayNode).add(firstItem(it).deepCopy()) }) shouldBe false
        }
    }
})
