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
 * BUSINESS-PROFILE-001 BIZ-3 인계 ①(BIZ-2 리뷰 MINOR-3·E-16): MASTER `BusinessProfileApiSpec`이 실제 응답 생성기로 고정한 골든 `business-profile-response-shapes-v1.golden.json`의
 * **ADMIN 바이트 동일 사본**을 `MasterResponseShape`의 사업자 모양으로 읽는다(10개 모양 — 키 집합 정확 일치, MERCHANT-CODE-001 §1.5 교훈: 배포 경계에서 두 앱이 갈라지지 않게).
 * 골든 값은 Demo 값이다(운영값 아님). 대조군: 키 추가·누락, 원문 `registrationNumber` 키, 진위확인 VERIFIED, 다른 거절의 existing, 중복 거절의 existing 누락, fieldErrors 문법·code 밖.
 */
class BusinessShapeGoldenSpec : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val resource = BusinessShapeGoldenSpec::class.java.getResource("/contracts/business-profile-response-shapes-v1.golden.json")!!
    val golden: JsonNode = mapper.readTree(resource.readText(Charsets.UTF_8))
    fun copy(name: String): ObjectNode = golden.get(name).deepCopy() as ObjectNode
    fun parses(shape: MasterResponseShape, node: JsonNode): Boolean = runCatching { shape.read(node) }.isSuccess
    fun firstItem(node: ObjectNode): ObjectNode = (node.get("items") as ArrayNode).get(0) as ObjectNode

    val shapes: Map<String, MasterResponseShape> = linkedMapOf(
        "list" to MasterResponseShape.BUSINESS_LIST, "lookupFound" to MasterResponseShape.BUSINESS_LOOKUP, "lookupNotFound" to MasterResponseShape.BUSINESS_LOOKUP,
        "detail" to MasterResponseShape.BUSINESS_DETAIL, "history" to MasterResponseShape.BUSINESS_HISTORY, "commandSucceeded" to MasterResponseShape.BUSINESS_COMMAND_SUCCEEDED,
        "commandReplayed" to MasterResponseShape.BUSINESS_COMMAND_SUCCEEDED, "duplicateRejected" to MasterResponseShape.BUSINESS_DUPLICATE_REJECTED,
        "otherRejected" to MasterResponseShape.COMMAND_REJECTED, "validationFailed" to MasterResponseShape.BUSINESS_VALIDATION_FAILED,
    )

    Given("BIZ-3 인계 ① the golden produced by MASTER (BIZ-2)") {
        Then("the ADMIN copy is byte-identical to the MASTER golden (no silent drift between the two apps)") {
            val master = File(System.getProperty("user.dir")).resolve("../master-api/src/test/resources/contracts/business-profile-response-shapes-v1.golden.json")
            master.isFile shouldBe true
            resource.readBytes().toList() shouldBe master.readBytes().toList()
        }

        Then("the golden has exactly the 10 shapes of §6.8 and each parses with its ADMIN shape, re-emitting the same document (key set and order exact)") {
            golden.propertyNames().toList() shouldBe shapes.keys.toList()
            shapes.forEach { (name, shape) ->
                withClue(name) {
                    val read = shape.read(golden.get(name))
                    mapper.readTree(mapper.writeValueAsString(read)) shouldBe golden.get(name)
                    mapper.writeValueAsString(read) shouldBe mapper.writeValueAsString(golden.get(name))
                }
            }
        }

        Then("control: one extra key or one missing key fails every shape (fail-closed)") {
            shapes.forEach { (name, shape) ->
                withClue(name) {
                    parses(shape, copy(name).also { it.put("unexpected", "x") }) shouldBe false
                    parses(shape, copy(name).also { it.remove(it.propertyNames().first()) }) shouldBe false
                }
            }
            parses(MasterResponseShape.BUSINESS_LIST, copy("list").also { firstItem(it).remove("representativeCount") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_DETAIL, copy("detail").also { (it.get("registration") as ObjectNode).remove("email") }) shouldBe false
        }

        Then("control: a plain registrationNumber key anywhere (list item, lookup item, detail business, duplicate existing) fails — BRN is always masked (D-BIZ-006 ②)") {
            parses(MasterResponseShape.BUSINESS_LIST, copy("list").also { firstItem(it).put("registrationNumber", "0000000000") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_LOOKUP, copy("lookupFound").also { firstItem(it).put("registrationNumber", "0000000000") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_DETAIL, copy("detail").also { (it.get("business") as ObjectNode).put("registrationNumber", "0000000000") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_DUPLICATE_REJECTED, copy("duplicateRejected").also { (it.get("existing") as ObjectNode).put("registrationNumber", "0000000000") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_LIST, copy("list").also { firstItem(it).put("registrationNumberMasked", "0000000000") }) shouldBe false
        }

        Then("control: verification other than NOT_CHECKED, two lookup items, an existing on another rejection and a duplicate without existing fail") {
            parses(MasterResponseShape.BUSINESS_DETAIL, copy("detail").also { (it.get("business") as ObjectNode).put("verificationStatus", "VERIFIED") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_LOOKUP, copy("lookupFound").also { (it.get("items") as ArrayNode).add(firstItem(it).deepCopy()) }) shouldBe false
            parses(MasterResponseShape.COMMAND_REJECTED, copy("otherRejected").also { it.set("existing", golden.get("duplicateRejected").get("existing").deepCopy()) }) shouldBe false
            parses(MasterResponseShape.BUSINESS_DUPLICATE_REJECTED, copy("duplicateRejected").also { it.remove("existing") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_DUPLICATE_REJECTED, copy("otherRejected")) shouldBe false
            parses(MasterResponseShape.BUSINESS_COMMAND_SUCCEEDED, copy("commandSucceeded").also { it.put("reference", "mer_01JC0000000000000000000001") }) shouldBe false
        }

        Then("BIZ-3 인계 ② fieldErrors: categories TOO_MANY and representatives[i].type parse; a path outside §4.6, an unknown code or a value key fail") {
            fun withErrors(vararg errors: Pair<String, String>) = copy("validationFailed").also { v ->
                val list = mapper.createArrayNode()
                errors.forEach { (p, c) -> list.add(mapper.createObjectNode().put("path", p).put("code", c)) }
                v.set("fieldErrors", list)
            }
            parses(MasterResponseShape.BUSINESS_VALIDATION_FAILED, withErrors("categories" to "TOO_MANY", "representatives[1].type" to "FORMAT", "contacts[4].primary" to "PRIMARY_MULTIPLE")) shouldBe true
            parses(MasterResponseShape.BUSINESS_VALIDATION_FAILED, withErrors("representatives[0].representativeType" to "FORMAT")) shouldBe false
            parses(MasterResponseShape.BUSINESS_VALIDATION_FAILED, withErrors("registrationNumber.value" to "FORMAT")) shouldBe false
            parses(MasterResponseShape.BUSINESS_VALIDATION_FAILED, withErrors("displayName" to "BAD_CODE")) shouldBe false
            // BIZ-3 리뷰 NIT-4: 오류 0개인 400은 모양 위반.
            parses(MasterResponseShape.BUSINESS_VALIDATION_FAILED, withErrors()) shouldBe false
            parses(
                MasterResponseShape.BUSINESS_VALIDATION_FAILED,
                copy("validationFailed").also { ((it.get("fieldErrors") as ArrayNode).get(0) as ObjectNode).put("value", "7319024865") },
            ) shouldBe false
        }

        Then("control: history changes accept only the §5.6 entry forms (value before/after, rows ended/added with fixed row keys)") {
            fun changes(node: ObjectNode): ArrayNode = ((node.get("items") as ArrayNode).get(1) as ObjectNode).get("changes") as ArrayNode
            parses(MasterResponseShape.BUSINESS_HISTORY, copy("history").also { (changes(it).get(0) as ObjectNode).put("field", "registrationNumber") }) shouldBe false
            parses(MasterResponseShape.BUSINESS_HISTORY, copy("history").also { (changes(it).get(1) as ObjectNode).remove("added") }) shouldBe false
            parses(
                MasterResponseShape.BUSINESS_HISTORY,
                copy("history").also { (((changes(it).get(1) as ObjectNode).get("ended") as ArrayNode).get(0) as ObjectNode).put("phoneNumberRaw", "01073190248") },
            ) shouldBe false
            parses(MasterResponseShape.BUSINESS_HISTORY, copy("history").also { (changes(it).get(0) as ObjectNode).set("ended", mapper.createArrayNode()) }) shouldBe false
        }
    }
})
