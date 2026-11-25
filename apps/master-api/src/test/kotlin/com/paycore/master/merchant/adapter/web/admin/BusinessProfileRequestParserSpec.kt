package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.REMOVE
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.address
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.category
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.contact
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.content
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.registration
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.representative
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies.with
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.BusinessFieldError
import com.paycore.master.merchant.domain.BusinessFieldErrorCode
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.RepresentativeType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.time.LocalDate

/**
 * BUSINESS-PROFILE-001 BIZ-2-3(§6.6 — 해석 단위, DB 없음): 구조 위반 → [BusinessCommandParse.Malformed](400 `REQUEST_MALFORMED`), 값 규칙 위반 → [BusinessCommandParse.Invalid]
 * (400 `BUSINESS_VALIDATION_FAILED` + path·code — **값 미포함**), 최대 크기 유효 본문 ≤ 16 KiB, 목록 검색(§6.2)·번호 조회(§6.3) 해석. 기대값은 명세 리터럴(구현 상수와 독립).
 * API 수준 fieldErrors·`admin_request` 0행은 BusinessProfileApiSpec이 확인한다.
 */
class BusinessProfileRequestParserSpec : BehaviorSpec({

    val number = "1234567890"
    fun create(map: Map<String, Any?>) = BusinessProfileRequestParser.create(BusinessBodies.json(map).toByteArray(Charsets.UTF_8))
    fun update(map: Map<String, Any?>) = BusinessProfileRequestParser.update(BusinessBodies.json(map).toByteArray(Charsets.UTF_8))
    fun complete(map: Map<String, Any?>) = BusinessProfileRequestParser.completeRegistration(BusinessBodies.json(map).toByteArray(Charsets.UTF_8))
    val createBase = BusinessBodies.createMap(number = number)
    val updateBase = BusinessBodies.updateMap(businessId = "biz_01JC0000000000000000000001", version = 3)
    fun errors(r: BusinessCommandParse<*>) = r.shouldBeInstanceOf<BusinessCommandParse.Invalid>().errors

    Given("a well-formed create body (individual REGISTERED — TC-01)") {
        Then("it parses into the command with the request order kept (fingerprint order) and the common fields") {
            val s = Submission()
            val cmd = create(BusinessBodies.createMap(s, number = number, content = BusinessBodies.corporation()))
                .shouldBeInstanceOf<BusinessCommandParse.Parsed<CreateBusinessCommand>>().command
            cmd.request.idempotencyKey.value shouldBe s.idempotencyKey
            cmd.request.actor.value shouldBe s.actor
            cmd.request.correlationId shouldBe s.correlationId
            cmd.registrationStatus shouldBe BusinessRegistrationStatus.REGISTERED
            cmd.businessType shouldBe BusinessType.CORPORATION
            cmd.registrationNumber.value shouldBe number
            cmd.profile.registration.corporateRegistrationNumber!!.value shouldBe "1101110000001"
            cmd.profile.registration.openingDate shouldBe LocalDate.parse("2026-01-01")
            cmd.profile.representatives.map { it.name.value to it.type } shouldBe listOf("이공동" to RepresentativeType.JOINT, "박공동" to RepresentativeType.JOINT)
            cmd.profile.categories.map { it.name.value } shouldBe listOf("도소매", "서비스", "소프트웨어", "전자상거래")
            cmd.profile.contacts.map { it.primary } shouldBe listOf(true, false)
        }
        Then("TC-04 a DRAFT minimum (type + number + name, empty lists, null optionals) parses") {
            create(BusinessBodies.createMap(status = "DRAFT", number = number, content = BusinessBodies.draftMinimal()))
                .shouldBeInstanceOf<BusinessCommandParse.Parsed<CreateBusinessCommand>>().command.registrationStatus shouldBe BusinessRegistrationStatus.DRAFT
        }
    }

    Given("structural violations (BIZ-2-3 — 400 REQUEST_MALFORMED, no field, no value)") {
        Then("unknown key, missing key, non-null field null, wrong JSON types, list caps, bad common fields, bad status, duplicate key, trailing token") {
            val reg = registration()
            val cases: Map<String, BusinessCommandParse<*>> = mapOf(
                "unknown key" to create(with(createBase, "verificationStatus", "VERIFIED")),
                "missing key" to create(with(createBase, "contacts", REMOVE)),
                "missing optional key (null must be explicit)" to create(with(createBase, "registration", with(reg, "englishName", REMOVE))),
                "businessType null" to create(with(createBase, "businessType", null)),
                "displayName null" to create(with(createBase, "displayName", null)),
                "registrationNumber number type" to create(with(createBase, "registrationNumber", 1234567890L)),
                "countryCode null" to create(with(createBase, "registration", with(reg, "countryCode", null))),
                "registration not an object" to create(with(createBase, "registration", "KR")),
                "representatives not an array" to create(with(createBase, "representatives", representative())),
                "row unknown key" to create(with(createBase, "representatives", listOf(with(representative(), "birthDate", "1990-01-01")))),
                "row field null" to create(with(createBase, "representatives", listOf(with(representative(), "name", null)))),
                "primary as string" to create(with(createBase, "contacts", listOf(contact(primary = "true")))),
                "primary null" to create(with(createBase, "contacts", listOf(contact(primary = null)))),
                "6 representatives (cap 5)" to create(with(createBase, "representatives", List(6) { representative("대표$it", "JOINT") })),
                "21 categories (cap 20)" to create(with(createBase, "categories", List(21) { category(if (it < 11) "BUSINESS_TYPE" else "BUSINESS_ITEM", "업$it") })),
                "3 addresses (cap 2)" to create(with(createBase, "addresses", listOf(address(), address("ACTUAL"), address("ACTUAL")))),
                "6 contacts (cap 5)" to create(with(createBase, "contacts", List(6) { contact("담당$it", primary = it == 0) })),
                "status INACTIVE" to create(with(createBase, "registrationStatus", "INACTIVE")),
                "actor outside admin namespace" to create(with(createBase, "actor", "system:demo-seed")),
                "bad idempotency key" to create(with(createBase, "idempotencyKey", "has space")),
                "update with registrationNumber (TC-13 — number is not editable)" to update(with(updateBase, "registrationNumber", number)),
                "update with registrationStatus" to update(with(updateBase, "registrationStatus", "REGISTERED")),
                "update version as string" to update(with(updateBase, "version", "3")),
                "update version negative" to update(with(updateBase, "version", -1)),
                "update version fraction" to update(with(updateBase, "version", 1.5)),
                "update bad businessId" to update(with(updateBase, "businessId", "mer_01JC0000000000000000000001")),
                "update reason number" to update(with(updateBase, "reason", 7)),
                "complete missing reason key" to complete(with(updateBase, "reason", REMOVE)),
            )
            cases.forEach { (name, result) -> withClue(name) { result shouldBe BusinessCommandParse.Malformed } }
            val raw = BusinessBodies.json(createBase)
            BusinessProfileRequestParser.create(raw.replaceFirst("{", "{\"displayName\":\"x\",").toByteArray()) shouldBe BusinessCommandParse.Malformed
            BusinessProfileRequestParser.create("$raw {}".toByteArray()) shouldBe BusinessCommandParse.Malformed
            BusinessProfileRequestParser.create("[]".toByteArray()) shouldBe BusinessCommandParse.Malformed
            BusinessProfileRequestParser.create(ByteArray(0)) shouldBe BusinessCommandParse.Malformed
        }
    }

    Given("value-rule violations (BIZ-2-3 m-9 — exact fieldErrors, path·code only)") {
        Then("TC-03 CORPORATION REGISTERED without the corporate number; TC-09 no representative; TC-11 no contact — create and complete-registration alike") {
            val corp = BusinessBodies.corporation()
            val noCorporate = with(corp, "registration", with(registration(corporate = null, englishName = "Demo Corp"), "corporateRegistrationNumber", null))
            val noRepresentative = with(BusinessBodies.individual(), "representatives", emptyList<Any>())
            val noContact = with(BusinessBodies.individual(), "contacts", emptyList<Any>())
            val expected = listOf(
                noCorporate to listOf(BusinessFieldError("registration.corporateRegistrationNumber", BusinessFieldErrorCode.REQUIRED)),
                noRepresentative to listOf(BusinessFieldError("representatives", BusinessFieldErrorCode.REQUIRED)),
                noContact to listOf(BusinessFieldError("contacts", BusinessFieldErrorCode.REQUIRED)),
            )
            expected.forEach { (body, errs) ->
                errors(create(BusinessBodies.createMap(number = number, content = body))) shouldBe errs
                errors(complete(BusinessBodies.updateMap(businessId = "biz_01JC0000000000000000000001", version = 0, content = body))) shouldBe errs
            }
        }
        Then("value formats are reported per path (request positions), sorted by path, without the input values") {
            val body = BusinessBodies.createMap(
                number = "123-45-678",
                content = content(
                    name = " 앞공백",
                    registration = registration(phone = "02-1234-5678", email = "no-at-sign", openingDate = "2026-02-30"),
                    representatives = listOf(representative(), representative("", "JOINT")),
                    categories = listOf(category("BUSINESS_TYPE", "서비스업"), category("UNKNOWN", "전자상거래")),
                    addresses = listOf(address(postal = "1234")),
                    contacts = listOf(contact(email = "a@b")),
                ),
            )
            val errs = errors(create(body))
            errs shouldBe listOf(
                BusinessFieldError("addresses[0].postalCode", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("categories[1].type", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("contacts[0].email", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("displayName", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("registration.email", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("registration.openingDate", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("registration.phoneNumber", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("registrationNumber", BusinessFieldErrorCode.FORMAT),
                BusinessFieldError("representatives[1].name", BusinessFieldErrorCode.REQUIRED),
            )
            val text = errs.toString()
            listOf("123-45-678", "02-1234-5678", "no-at-sign", "2026-02-30", "앞공백", "1234", "a@b", "UNKNOWN").forEach { text shouldNotContain it }
        }
        Then("aggregate rules run on the request list positions (DUPLICATE category, SOLE with others, INDIVIDUAL corporate number NOT_ALLOWED, country)") {
            val body = BusinessBodies.createMap(
                status = "DRAFT", number = number,
                content = content(
                    registration = registration(corporate = "1101110000001", countryCode = "US"),
                    representatives = listOf(representative(), representative("임꺽정", "JOINT")),
                    categories = listOf(category("BUSINESS_TYPE", "서비스업"), category("BUSINESS_TYPE", "서비스업")),
                ),
            )
            errors(create(body)) shouldBe listOf(
                BusinessFieldError("categories[1].name", BusinessFieldErrorCode.DUPLICATE),
                BusinessFieldError("registration.corporateRegistrationNumber", BusinessFieldErrorCode.NOT_ALLOWED),
                BusinessFieldError("registration.countryCode", BusinessFieldErrorCode.NOT_ALLOWED),
                BusinessFieldError("representatives", BusinessFieldErrorCode.SOLE_WITH_OTHERS),
            )
        }
        Then("MINOR-2 (E-10 ③ pinned): 11 business types + 9 items = 20 rows passes the structural cap and is the field error [categories TOO_MANY] (not REQUEST_MALFORMED)") {
            val categories = List(11) { category("BUSINESS_TYPE", "업태$it") } + List(9) { category("BUSINESS_ITEM", "종목$it") }
            errors(create(BusinessBodies.createMap(status = "DRAFT", number = number, content = content(categories = categories)))) shouldBe
                listOf(BusinessFieldError("categories", BusinessFieldErrorCode.TOO_MANY))
            // 대조: 21행은 구조 상한 초과 → REQUEST_MALFORMED.
            create(BusinessBodies.createMap(number = number, content = content(categories = categories + category("BUSINESS_ITEM", "종목9")))) shouldBe
                BusinessCommandParse.Malformed
        }
        Then("update parses with the DRAFT common rules only — REGISTERED-only requirements are judged inside the TX on the locked state (BIZ-2-11)") {
            update(with(updateBase, "representatives", emptyList<Any>())).shouldBeInstanceOf<BusinessCommandParse.Parsed<UpdateBusinessCommand>>()
            errors(update(with(updateBase, "reason", ""))) shouldBe listOf(BusinessFieldError("reason", BusinessFieldErrorCode.REQUIRED))
            // SEC-BIZ-001 M-1(BR-BIZ-305): 사유 칸 카드번호 패턴은 값 단계 필드 오류(공통 벡터 전부 — update·complete 둘 다), 카드번호가 아닌 숫자열은 통과.
            com.paycore.master.merchant.domain.CardNumberVectors.CARD_LIKE.forEach { reason ->
                errors(update(with(updateBase, "reason", reason))) shouldBe listOf(BusinessFieldError("reason", BusinessFieldErrorCode.CARD_NUMBER_PATTERN))
                errors(complete(with(updateBase, "reason", reason))) shouldBe listOf(BusinessFieldError("reason", BusinessFieldErrorCode.CARD_NUMBER_PATTERN))
            }
            com.paycore.master.merchant.domain.CardNumberVectors.NOT_CARD.forEach { reason ->
                update(with(updateBase, "reason", reason)).shouldBeInstanceOf<BusinessCommandParse.Parsed<UpdateBusinessCommand>>()
            }
            errors(update(with(updateBase, "displayName", "가".repeat(101)))) shouldBe listOf(BusinessFieldError("displayName", BusinessFieldErrorCode.TOO_LONG))
        }
    }

    Given("BIZ-2-3 the largest valid body (every list at its cap, every text at its maximum length in 4-byte characters)") {
        Then("it is at most 16 KiB (the signed channel limit) and parses") {
            val wide = "😀" // U+1F600 — UTF-8 4바이트, 1 code point
            fun text(n: Int, tag: Int = 0) = wide.repeat(n - 1) + ('가' + tag)
            val email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
            email.length shouldBe 254
            val body = BusinessBodies.updateMap(
                businessId = "biz_01JC0000000000000000000001", version = Long.MAX_VALUE, reason = text(200),
                content = content(
                    type = "CORPORATION", name = text(100),
                    registration = registration(corporate = "1101110000001", englishName = "E".repeat(100), phone = "012345678901", email = email),
                    representatives = List(5) { representative(text(50, it), "JOINT") },
                    categories = List(20) { category(if (it < 10) "BUSINESS_TYPE" else "BUSINESS_ITEM", text(50, it)) },
                    addresses = listOf(address("REGISTERED", "12345", text(200, 1), text(200, 2)), address("ACTUAL", "54321", text(200, 3), text(200, 4))),
                    contacts = List(5) { contact(text(50, it), email, "012345678901", primary = it == 0) },
                ),
            )
            val bytes = BusinessBodies.json(body).toByteArray(Charsets.UTF_8)
            bytes.size shouldBeLessThanOrEqual 16 * 1024
            BusinessProfileRequestParser.completeRegistration(bytes).shouldBeInstanceOf<BusinessCommandParse.Parsed<*>>()
        }
    }

    Given("the list search body (§6.2 — strict, every field optional, no registration number)") {
        fun parse(json: String) = BusinessSearchRequestParser.parse(json.toByteArray(Charsets.UTF_8))
        Then("all fields parse; KST dates become the 00:00 KST boundaries (createdTo inclusive)") {
            val f = parse(
                """{"displayNamePrefix":"50%_!","businessType":"CORPORATION","registrationStatus":"DRAFT","createdFrom":"2026-11-17","createdTo":"2026-11-17",""" +
                    """"after":"biz_01JC0000000000000000000001","limit":50}""",
            )!!
            f.displayNamePrefix!!.value shouldBe "50%_!"
            f.businessType shouldBe BusinessType.CORPORATION
            f.registrationStatus shouldBe BusinessRegistrationStatus.DRAFT
            f.createdAtFrom shouldBe Instant.parse("2026-11-16T15:00:00Z")
            f.createdAtBefore shouldBe Instant.parse("2026-11-17T15:00:00Z")
            f.limit shouldBe 50
            parse("{}")!!.limit shouldBe 20
        }
        Then("a registrationNumber field, unknown values, bad dates, from > to, bad cursor, limit out of range, wrong types are rejected") {
            listOf(
                """{"registrationNumber":"1234567890"}""", """{"businessType":"FOREIGN"}""", """{"registrationStatus":"draft"}""",
                """{"createdFrom":"2026-02-30"}""", """{"createdFrom":"2026-11-18","createdTo":"2026-11-17"}""", """{"createdFrom":"20261117"}""",
                """{"after":"mer_01JC0000000000000000000001"}""", """{"limit":0}""", """{"limit":51}""", """{"limit":"20"}""", """{"displayNamePrefix":""}""",
                """{"displayNamePrefix":" a"}""", """{"limit":1,"limit":2}""", """{} []""", "[]",
            ).forEach { withClue(it) { parse(it) shouldBe null } }
        }
    }

    Given("the registration number lookup body (§6.3 — exactly one 10-digit string)") {
        fun parse(json: String) = BusinessLookupRequestParser.parse(json.toByteArray(Charsets.UTF_8))
        Then("only {\"registrationNumber\":\"<10 digits>\"} parses") {
            parse("""{"registrationNumber":"0123456789"}""")!!.value shouldBe "0123456789"
            listOf(
                """{"registrationNumber":"123-45-67890"}""", """{"registrationNumber":"123456789"}""", """{"registrationNumber":1234567890}""",
                """{"registrationNumber":"1234567890","x":1}""", """{"registrationNumber":"1234567890","registrationNumber":"1234567890"}""",
                """{"registrationNumber":"1234567890"} {}""", """{}""", """{"registrationNumber":null}""",
            ).forEach { withClue(it) { parse(it) shouldBe null } }
        }
    }
})
