package com.paycore.master.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

/**
 * 식별자·값 객체 형식(관례 — MASTER-DOMAIN-001 명세 §6)과 사업자등록번호 노출 통제(D-MD-008), 효력 기간 [from, to)(D-MD-005).
 */
class MasterValuesSpec : BehaviorSpec({

    Given("MASTER identifiers ('<prefix>_' + 26 Crockford base32 characters)") {
        Then("a well-formed id of each kind is accepted") {
            val ulid = "01HZY3B0Q7KJ8M9N4P5R6S7T8V"
            listOf(
                { BusinessId("biz_$ulid") }, { MerchantId("mer_$ulid") }, { MerchantSiteId("site_$ulid") },
                { MerchantAccountId("mid_$ulid") }, { ContractId("ctr_$ulid") }, { ContractServiceAgreementId("csa_$ulid") },
                { SiteAssignmentId("asg_$ulid") }, { PaymentMethodActivationId("act_$ulid") },
            ).forEach { it() }
        }

        Then("wrong prefix, lower case, excluded letters (I L O U), wrong length and the other kind's prefix are rejected without echoing input") {
            val ulid = "01HZY3B0Q7KJ8M9N4P5R6S7T8V"
            listOf(
                "mer_$ulid", "mid_${ulid.lowercase()}", "mid_01HZY3B0Q7KJ8M9N4P5R6S7T8I", "mid_01HZY3B0Q7KJ8M9N4P5R6S7T8L",
                "mid_01HZY3B0Q7KJ8M9N4P5R6S7T8O", "mid_01HZY3B0Q7KJ8M9N4P5R6S7T8U", "mid_$ulid" + "0", "mid_" + ulid.drop(1),
                "mid-$ulid", " mid_$ulid", "",
            ).forEach { value ->
                val error = shouldThrow<IllegalArgumentException> { MerchantAccountId(value) }
                if (value.isNotEmpty()) error.message!! shouldNotContain value
            }
        }

        Then("an actor reference is 1..64 characters of [A-Za-z0-9._:@-]") {
            ActorRef("ops:alice@example").value shouldBe "ops:alice@example"
            listOf("", "a b", "x".repeat(65), "운영자").forEach { shouldThrow<IllegalArgumentException> { ActorRef(it) } }
        }

        Then("a display name is 1..100 code points without control characters, unpaired surrogates or edge spaces (no implicit trim)") {
            DisplayName("가맹점 😀 Shop").value shouldBe "가맹점 😀 Shop"
            DisplayName("가".repeat(100))
            listOf("", " a", "a ", "a\nb", "a\uD800", "가".repeat(101)).forEach { shouldThrow<IllegalArgumentException> { DisplayName(it) } }
        }
    }

    Given("a domestic business registration number (D-MD-008)") {
        Then("only 10 ASCII digits are accepted; hyphenated or full-width forms are rejected (no implicit normalization)") {
            BusinessRegistrationNumber("1234567890").value shouldBe "1234567890"
            listOf("123-45-67890", "123456789", "12345678901", "１２３４５６７８９０", "12345 7890").forEach {
                shouldThrow<IllegalArgumentException> { BusinessRegistrationNumber(it) }
            }
        }

        Then("toString and masked() never reveal more than the first 3 digits; the Business toString is masked too") {
            val number = BusinessRegistrationNumber("1234567890")
            number.masked() shouldBe "123*******"
            number.toString() shouldNotContain "4567890"
            Business.register(MasterFixture.businessId(), number, DisplayName("B"), MasterFixture.t0, MasterFixture.operator)
                .toString() shouldNotContain "4567890"
        }
    }

    Given("a site channel identifier (D-MD-008 duplicate checks rely on one canonical form)") {
        Then("lower-case WEB host names and dotted APP ids are accepted") {
            SiteChannel(ChannelType.WEB, "shop.example.co.kr")
            // 마지막 라벨이 숫자가 아니면 도메인이다(0x 접두지만 16진수가 아닌 라벨, 0x가 앞 라벨에만 있는 경우).
            SiteChannel(ChannelType.WEB, "shop.0xg")
            SiteChannel(ChannelType.WEB, "0x7f.example")
            SiteChannel(ChannelType.APP, "com.example.shop_app")
        }

        Then("upper case, scheme, path, port, single label, spaces and IP literals are rejected (D-MD-009, D-MD-014)") {
            listOf(
                "Shop.example.com", "https://shop.example.com", "shop.example.com/a", "shop.example.com:443", "localhost", "shop .com",
                "192.168.0.1", "10.0.0.1", "shop.example.123", "::1", "[2001:db8::1]",
                // WHATWG "ends in a number": 마지막 라벨이 0x 16진수면 브라우저가 IPv4로 해석한다(review n-2, D-MD-014).
                "a.0x7f", "0x7f.0x1", "shop.0x", "shop.example.0xff",
            )
                .forEach { shouldThrow<IllegalArgumentException> { SiteChannel(ChannelType.WEB, it) } }
            listOf("Com.Example.Shop", "shop", "com..shop").forEach { shouldThrow<IllegalArgumentException> { SiteChannel(ChannelType.APP, it) } }
        }
    }

    Given("an effective period [from, to) in UTC instants (D-MD-005)") {
        val from = Instant.parse("2026-10-09T00:00:00Z")
        val to = Instant.parse("2026-10-10T00:00:00Z")

        Then("from is included and to is excluded; an empty or inverted period is rejected") {
            val period = EffectivePeriod(from, to)
            mapOf(
                "from" to period.contains(from),
                "just before to" to period.contains(to.minusMillis(1)),
                "to" to period.contains(to),
                "before from" to period.contains(from.minusMillis(1)),
            ) shouldBe mapOf("from" to true, "just before to" to true, "to" to false, "before from" to false)
            shouldThrow<IllegalArgumentException> { EffectivePeriod(from, from) }
            shouldThrow<IllegalArgumentException> { EffectivePeriod(to, from) }
        }

        Then("periods that only touch do not overlap; open-ended periods overlap everything after their start") {
            mapOf(
                "touching" to EffectivePeriod(from, to).overlaps(EffectivePeriod(to)),
                "inside open" to EffectivePeriod(to, to.plusSeconds(1)).overlaps(EffectivePeriod(from)),
                "before open" to EffectivePeriod(from.minusSeconds(10), from).overlaps(EffectivePeriod(from)),
            ) shouldBe mapOf("touching" to false, "inside open" to true, "before open" to false)
        }

        Then("ofMillis truncates to the stored millisecond precision and rejects a period that becomes empty (review m-4)") {
            EffectivePeriod.ofMillis(from.plusNanos(1_999_999), to.plusNanos(500_000)) shouldBe
                EffectivePeriod(from.plusMillis(1), to)
            shouldThrow<MasterRuleViolation> { EffectivePeriod.ofMillis(from.plusNanos(100_000), from.plusNanos(400_000)) }.rejection shouldBe
                MasterRejection.EFFECTIVE_PERIOD_EMPTY
            EffectivePeriod(from.plusNanos(1), null).isMillisPrecise shouldBe false
            EffectivePeriod.ofMillis(from.plusNanos(1), null).isMillisPrecise shouldBe true
        }

        Then("an open-ended period never ends; a closed one has ended at and after its end") {
            mapOf(
                "open" to EffectivePeriod(from).hasEndedAt(to.plusSeconds(86_400 * 365L)),
                "at end" to EffectivePeriod(from, to).hasEndedAt(to),
                "before end" to EffectivePeriod(from, to).hasEndedAt(to.minusMillis(1)),
            ) shouldBe mapOf("open" to false, "at end" to true, "before end" to false)
        }
    }
})
